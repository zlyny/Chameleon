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

// mfkey32 认证四元组：卡生成的明文 NT 与读卡器发来的密文 NR/AR
struct Mfkey32Rec {
    uint32_t nt;
    uint32_t nr;
    uint32_t ar;
};

// 复核：密钥能否解释一条认证记录（对齐上位机 crypto1.py 的
// mfkey32_is_reader_has_key）——用密钥初始化 Crypto1，走一遍
// 认证密钥流，验证明文 AR 是否等于 NT 的第 64 步后继
bool keyMatchesRecord(uint32_t uid, const Mfkey32Rec& rec, uint64_t key) {
    struct Crypto1State s;
    crypto1_init(&s, key);
    crypto1_word(&s, uid ^ rec.nt, 0);
    crypto1_word(&s, rec.nr, 1);
    return rec.ar == (crypto1_word(&s, 0, 0) ^ prng_successor(rec.nt, 64));
}

// mfkey32v2 单对求解（移植自 software/src/mfkey32v2.c）：由记录 a 的
// keystream 恢复候选状态并回滚出密钥，再用记录 b 前向验证。
// 命中返回 true 并写入 *outKey
bool mfkey32Pair(uint32_t uid, const Mfkey32Rec& a, const Mfkey32Rec& b, uint64_t* outKey) {
    const uint32_t p64a = prng_successor(a.nt, 64);
    const uint32_t p64b = prng_successor(b.nt, 64);
    struct Crypto1State* s = lfsr_recovery32(a.ar ^ p64a, 0);
    if (s == nullptr) {
        return false;
    }
    bool found = false;
    for (struct Crypto1State* t = s; t->odd | t->even; ++t) {
        // 回滚到认证前的初始状态：ks2(0) -> ks1(nr) -> ks0(uid^nt)
        lfsr_rollback_word(t, 0, 0);
        lfsr_rollback_word(t, a.nr, 1);
        lfsr_rollback_word(t, uid ^ a.nt, 0);
        crypto1_get_lfsr(t, outKey);

        // 用记录 b 前向验证候选密钥
        crypto1_word(t, uid ^ b.nt, 0);
        crypto1_word(t, b.nr, 1);
        if (b.ar == (crypto1_word(t, 0, 0) ^ p64b)) {
            found = true;
            break;
        }
    }
    free(s);
    return found;
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
 * gen2 卡破解 KeyB 时先按 dist=161 求解，无候选则回退 dist=160
 * 重解一次（不同批次卡的 PRNG 步进存在 161/160 两种实测值）。
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

    // 先解包到本地缓冲并立即释放 JNI 数组引用，
    // 之后（含 dist 回退重解）不再触碰 JNI 内存
    std::vector<std::pair<uint32_t, uint32_t>> nts;
    nts.reserve(pairCount);
    for (jsize i = 0; i < pairCount; i++) {
        nts.emplace_back(static_cast<uint32_t>(pairs[i] >> 32),
                         static_cast<uint32_t>(pairs[i] & 0xFFFFFFFFULL));
    }
    env->ReleaseLongArrayElements(ntPairs, pairs, JNI_ABORT);

    if (nts.empty()) {
        return env->NewLongArray(0);
    }

    // 首组 NT 判定卡的 StaticNested 漏洞代次，决定 PRNG 前进步数
    const uint32_t nt1 = nts.front().first;
    uint32_t dist = 0;
    bool vulnerable = true;
    if (nt1 == kStaticGen1Nt) {
        dist = 160; // st gen1
    } else if (nt1 == kStaticGen2Nt) { // st gen2
        if (targetType == 0x61) {
            dist = 161;
        } else if (targetType == 0x60) {
            dist = 160;
        } else {
            vulnerable = false;
        }
    } else {
        // 不属于已知 Static 漏洞卡
        vulnerable = false;
    }
    if (!vulnerable) {
        return env->NewLongArray(0);
    }

    // 以初始步数 baseDist 推导每组 (ntp, ks1) 后求解：第 i 组 NT 的
    // 前进步数为 baseDist + 160*i（Static 卡 PRNG 随认证次数规律前进）
    auto solve = [&nts, uid, env](uint32_t baseDist) -> jlongArray {
        std::vector<NtpKs1> pNK;
        pNK.reserve(nts.size());
        uint32_t dist = baseDist;
        for (const auto& nt : nts) {
            uint32_t nttest = prng_successor(nt.first, dist);
            uint32_t ks1 = nt.second ^ nttest;
            pNK.push_back(NtpKs1{nttest, ks1});
            dist += 160;
        }
        uint32_t keyCount = 0;
        uint64_t* keys = nested(pNK.data(), pNK.size(),
                                static_cast<uint32_t>(uid), &keyCount);
        jlongArray result = toJavaKeys(env, keys, keyCount);
        free(keys);
        return result;
    };

    jlongArray result = solve(dist);
    // gen2 卡 KeyB 的 PRNG 前进步数实测存在 161/160 两种，首次求解
    // 无候选时以 160 回退重解，避免漏掉正确密钥
    if (env->GetArrayLength(result) == 0 && dist == 161) {
        result = solve(160);
    }
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

/**
 * mfkey32 攻击求解（移植自 software/src/mfkey32v2.c，对齐根目录
 * dump_mf1_elog.py 的破解编排）。
 *
 * 输入同一 (uid, block, key) 分组内的全部认证记录（nts/nrs/ars 等长，
 * 每元素为一条记录的明文 NT / 密文 NR / 密文 AR）：
 * - 记录两两组合调用 mfkey32v2 算法（记录 a 恢复候选 -> 记录 b 验证）；
 * - 命中的密钥再对全组记录复核（keyMatchesRecord），全部失败的视为误报丢弃；
 * - 已被找到的密钥解释的记录对跳过（对齐 py 版 validated 优化，降低组合开销）。
 *
 * 返回去重后的密钥列表（48bit 密钥数值）；记录不足 2 条返回空数组。
 */
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_example_chameleon_jni_ChameleonNative_mfkey32Recover(
        JNIEnv* env,
        jobject /* this */,
        jlong uid,
        jlongArray nts,
        jlongArray nrs,
        jlongArray ars) {
    const jsize n = env->GetArrayLength(nts);
    if (n < 2 || env->GetArrayLength(nrs) != n || env->GetArrayLength(ars) != n) {
        return env->NewLongArray(0);
    }

    // 解包到本地缓冲并立即释放 JNI 引用，之后不再触碰 JNI 内存
    std::vector<Mfkey32Rec> recs(n);
    {
        jlong* pNt = env->GetLongArrayElements(nts, nullptr);
        jlong* pNr = env->GetLongArrayElements(nrs, nullptr);
        jlong* pAr = env->GetLongArrayElements(ars, nullptr);
        for (jsize i = 0; i < n; i++) {
            recs[i] = {static_cast<uint32_t>(pNt[i]),
                       static_cast<uint32_t>(pNr[i]),
                       static_cast<uint32_t>(pAr[i])};
        }
        env->ReleaseLongArrayElements(nts, pNt, JNI_ABORT);
        env->ReleaseLongArrayElements(nrs, pNr, JNI_ABORT);
        env->ReleaseLongArrayElements(ars, pAr, JNI_ABORT);
    }
    const uint32_t u = static_cast<uint32_t>(uid);

    std::vector<uint64_t> foundKeys;
    std::vector<uint8_t> validated(n, 0);
    for (jsize i = 0; i < n; i++) {
        for (jsize j = i + 1; j < n; j++) {
            // 两条记录均已可被已知密钥解释时跳过该组合（py 版 validated 优化）
            if (validated[i] && validated[j]) {
                continue;
            }
            uint64_t key = 0;
            if (!mfkey32Pair(u, recs[i], recs[j], &key)) {
                continue;
            }
            // 全组复核：能解释的记录数 > 0 才采纳，并标记这些记录已解释
            uint32_t matches = 0;
            for (jsize k = 0; k < n; k++) {
                if (keyMatchesRecord(u, recs[k], key)) {
                    validated[k] = 1;
                    matches++;
                }
            }
            if (matches == 0) {
                continue;
            }
            bool duplicate = false;
            for (uint64_t known : foundKeys) {
                if (known == key) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                foundKeys.push_back(key);
            }
        }
    }
    return toJavaKeys(env, foundKeys.data(), foundKeys.size());
}

/**
 * mfkey32 单条记录复核：验证密钥能否解释该条认证记录
 * （uid ^ nt 前向走密钥流，比较 ar）。供上层统计"复核通过 n/m 条记录"。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_chameleon_jni_ChameleonNative_mfkey32Verify(
        JNIEnv* /* env */,
        jobject /* this */,
        jlong uid,
        jlong nt,
        jlong nr,
        jlong ar,
        jlong key) {
    const Mfkey32Rec rec = {static_cast<uint32_t>(nt),
                            static_cast<uint32_t>(nr),
                            static_cast<uint32_t>(ar)};
    return keyMatchesRecord(static_cast<uint32_t>(uid), rec,
                            static_cast<uint64_t>(key));
}
