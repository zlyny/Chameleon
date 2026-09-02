#include <jni.h>
#include <string>
#include <vector>

extern "C" {
#include "crypto1/crapto1.h"
#include "crypto1/nested_util.h"
}

namespace {

// StaticNested 漏洞代次判定（与上位机 staticnested.c 一致）：
// nt1 == 0x01200145 -> gen1 固定 dist=160；
// nt1 == 0x009080A2 -> gen2，KeyA 用 160、KeyB 用 161
constexpr uint32_t kStaticGen1Nt = 0x01200145;
constexpr uint32_t kStaticGen2Nt = 0x009080A2;

uint64_t packKey(uint64_t lfsr) {
    // crypto1_get_lfsr 返回的 48bit 密钥，取低 48 位
    return lfsr & 0xFFFFFFFFFFFFULL;
}

// C 候选密钥数组转 Java long[]（不做释放，keys 的释放由调用方负责）
jlongArray toJavaKeys(JNIEnv* env, const uint64_t* keys, uint32_t keyCount) {
    jlongArray result = env->NewLongArray(keyCount);
    if (keyCount > 0) {
        std::vector<jlong> boxed(keyCount);
        for (uint32_t i = 0; i < keyCount; i++) {
            boxed[i] = static_cast<jlong>(packKey(keys[i]));
        }
        env->SetLongArrayRegion(result, 0, keyCount, boxed.data());
    }
    return result;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_chameleon_jni_ChameleonNative_nativeVersion(
        JNIEnv* env,
        jobject /* this */) {
    std::string version = "chameleon-native 0.2.0";
    return env->NewStringUTF(version.c_str());
}

/**
 * Static Nested 攻击求解（移植自 ChameleonUltra software/src/staticnested.c）。
 *
 * 参数均按无符号 32 位解释：
 * - uid:        卡片 UID（4 字节数值）
 * - targetType: 目标密钥类型 0x60=KeyA / 0x61=KeyB
 * - ntPairs:    每个元素打包一组 (nt << 32 | nt_enc)，至少 2 组
 *
 * 返回候选密钥列表（每个元素为 48bit 密钥，按命中概率降序），
 * 非法 NT（非 Static 漏洞卡）返回空数组。
 */
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_example_chameleon_jni_ChameleonNative_staticnestedRecover(
        JNIEnv* env,
        jobject /* this */,
        jlong uid,
        jint targetType,
        jlongArray ntPairs) {
    const jsize pairCount = env->GetArrayLength(ntPairs);
    jlong* pairs = env->GetLongArrayElements(ntPairs, nullptr);

    std::vector<NtpKs1> pNK;
    bool vulnerable = true;
    uint32_t dist = 0;
    bool distResolved = false;

    for (jsize i = 0; i < pairCount; i++) {
        uint32_t nt1 = static_cast<uint32_t>(pairs[i] >> 32);
        uint32_t nt2 = static_cast<uint32_t>(pairs[i] & 0xFFFFFFFFULL);

        // 首组 NT 判定卡的 StaticNested 漏洞代次，决定 PRNG 前进步数
        if (!distResolved) {
            if (nt1 == kStaticGen1Nt) {
                dist = 160; // st gen1
            } else if (nt1 == kStaticGen2Nt) { // st gen2
                if (targetType == 0x61) {
                    dist = 161;
                } else if (targetType == 0x60) {
                    dist = 160;
                } else {
                    vulnerable = false;
                    break;
                }
            } else {
                // 不属于已知 Static 漏洞卡
                vulnerable = false;
                break;
            }
            distResolved = true;
        }

        uint32_t nttest = prng_successor(nt1, dist);
        uint32_t ks1 = nt2 ^ nttest;
        pNK.push_back(NtpKs1{nttest, ks1});
        dist += 160;
    }
    env->ReleaseLongArrayElements(ntPairs, pairs, JNI_ABORT);

    if (!vulnerable || pNK.empty()) {
        return env->NewLongArray(0);
    }

    uint32_t keyCount = 0;
    uint64_t* keys = nested(pNK.data(), pNK.size(),
                            static_cast<uint32_t>(uid), &keyCount);

    jlongArray result = toJavaKeys(env, keys, keyCount);
    free(keys);
    return result;
}

/**
 * Weak PRNG Nested 攻击求解（移植自 ChameleonUltra 上位机 software/src/nested.c）。
 *
 * 与 StaticNested 的区别：Weak 卡的 NT 持续前进，明文 NT 与加密 NT 的距离
 * 未知，故固件先测量 PRNG 前进步数 [dist]（MF1_DETECT_NT_DIST）。因时间
 * 抖动，真实距离在 dist±14 附近，算法在该范围内逐个前推枚举真实 NT，
 * 用采集到的奇偶位（par）经 valid_nonce 筛选出可信的 (ntp, ks1) 组合，
 * 最后复用 nested()（nested_util.c）做状态回滚求解。
 *
 * 参数均按无符号解释：
 * - uid:      卡片 UID（4 字节数值）
 * - dist:     固件测得的 PRNG 前进步数
 * - ntPairs:  采集到的 NT 对，每个元素打包 (nt shl 32) or ntEnc
 * - parities: 与 ntPairs 一一对应的奇偶位字节（低 3bit 有效）
 *
 * 返回候选密钥列表（48bit 密钥，按命中概率降序）；
 * 无可信 NT 组合（采集数据异常，可重采）返回空数组。
 */
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_example_chameleon_jni_ChameleonNative_nestedRecover(
        JNIEnv* env,
        jobject /* this */,
        jlong uid,
        jint dist,
        jlongArray ntPairs,
        jbyteArray parities) {
    const jsize pairCount = env->GetArrayLength(ntPairs);
    if (pairCount == 0 || env->GetArrayLength(parities) != pairCount) {
        return env->NewLongArray(0);
    }
    jlong* pairs = env->GetLongArrayElements(ntPairs, nullptr);
    jbyte* pars = env->GetByteArrayElements(parities, nullptr);

    std::vector<NtpKs1> pNK;
    for (jsize i = 0; i < pairCount; i++) {
        const uint32_t nt1 = static_cast<uint32_t>(pairs[i] >> 32);
        const uint32_t ntEnc = static_cast<uint32_t>(pairs[i] & 0xFFFFFFFFULL);

        // 奇偶位低 3bit 逐位展开（par=0 时原版走 memset 分支，结果一致）
        uint8_t parArr[3];
        for (int m = 0; m < 3; m++) {
            parArr[m] = (pars[i] >> m) & 0x01;
        }

        // 在 [dist-14, dist+14] 内枚举真实 NT，奇偶位匹配的进入求解
        uint32_t nttest = prng_successor(nt1, dist - 14);
        for (int m = dist - 14; m <= dist + 14; m++) {
            uint32_t ks1 = ntEnc ^ nttest;
            if (valid_nonce(nttest, ntEnc, ks1, parArr)) {
                pNK.push_back(NtpKs1{nttest, ks1});
            }
            nttest = prng_successor(nttest, 1);
        }
    }
    env->ReleaseLongArrayElements(ntPairs, pairs, JNI_ABORT);
    env->ReleaseByteArrayElements(parities, pars, JNI_ABORT);

    if (pNK.empty()) {
        return env->NewLongArray(0);
    }

    uint32_t keyCount = 0;
    uint64_t* keys = nested(pNK.data(), pNK.size(),
                            static_cast<uint32_t>(uid), &keyCount);

    jlongArray result = toJavaKeys(env, keys, keyCount);
    free(keys);
    return result;
}
