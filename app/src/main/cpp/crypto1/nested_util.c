/*
 * nested_util.c —— Static Nested 密钥恢复核心
 *
 * 移植自 ChameleonUltra 上位机 software/src/nested_util.c，与原版差异：
 * 1. pthread 四线程分片改为单线程顺序求解（Android 侧由 Kotlin 协程
 *    调度到 Dispatchers.Default，避免 JNI 内自建线程池）；
 * 2. 修复 uniqsort 原版读 possibleKeys[i + 1] 的末元素越界
 *    （Android scudo 页对齐分配下触发 SIGSEGV）；
 * 3. 修复空候选段 --kcount 的 uint32 下溢。
 */
#include <stdlib.h>
#include "parity.h"
#include "nested_util.h"

#define MEM_CHUNK               10000
#define TRY_KEYS                50

typedef struct {
    uint64_t       key;
    int            count;
} countKeys;

inline static int compar_int(const void *a, const void *b) {
    if (*(uint64_t *)b == *(uint64_t *)a) return 0;
    if (*(uint64_t *)b < * (uint64_t *)a) return 1;
    return -1;
}

// Compare countKeys structure
int compar_special_int(const void *a, const void *b) {
    return (((countKeys *)b)->count - ((countKeys *)a)->count);
}

// keys qsort and unique.
countKeys *uniqsort(uint64_t *possibleKeys, uint32_t size) {
    unsigned int i, j = 0;
    int count = 0;
    countKeys *our_counts;

    qsort(possibleKeys, size, sizeof(uint64_t), compar_int);

    our_counts = calloc(size, sizeof(countKeys));
    if (our_counts == NULL) {
        return NULL;
    }

    for (i = 0; i < size; i++) {
        // 上位机原版读 possibleKeys[i + 1]，i 为末元素时越界 8 字节：
        // PC 堆分配的尾部 padding 掩盖了它，Android scudo 对大块分配
        // 按页精确映射，数组末尾紧邻未映射页，越界读直接 SIGSEGV。
        // 末元素无后继必走 else 分支，结果与原版语义一致。
        if (i + 1 < size && possibleKeys[i + 1] == possibleKeys[i]) {
            count++;
        } else {
            our_counts[j].key = possibleKeys[i];
            our_counts[j].count = count;
            j++;
            count = 0;
        }
    }
    qsort(our_counts, j, sizeof(countKeys), compar_special_int);
    return (our_counts);
}

uint64_t *nested(NtpKs1 *pNK, uint32_t sizePNK, uint32_t authuid, uint32_t *keyCount) {
    *keyCount = 0;
    uint32_t i, kcount = 0;
    uint64_t *keys = NULL;
    struct Crypto1State *revstate, *revstate_start = NULL;
    uint64_t lfsr = 0;

    for (i = 0; i < sizePNK; i++) {
        uint32_t nt_probe = pNK[i].ntp ^ authuid;
        uint32_t ks1 = pNK[i].ks1;

        // And finally recover the first 32 bits of the key
        revstate = lfsr_recovery32(ks1, nt_probe);
        revstate_start = revstate;

        while ((revstate->odd != 0x0) || (revstate->even != 0x0)) {
            lfsr_rollback_word(revstate, nt_probe, 0);
            crypto1_get_lfsr(revstate, &lfsr);
            if (((kcount % MEM_CHUNK) == 0) || (kcount >= *keyCount)) {
                *keyCount += MEM_CHUNK;
                void *tmp = realloc(keys, *keyCount * sizeof(uint64_t));
                if (tmp == NULL) {
                    *keyCount = 0;
                    free(keys);
                    free(revstate_start);
                    return NULL;
                }
                keys = (uint64_t *)tmp;
            }
            keys[kcount] = lfsr;
            kcount++;
            revstate++;
        }
        if (kcount > 0) {
            --kcount; // 丢弃每段末位候选（对齐上位机行为）；空段防 uint32 下溢
        }
        free(revstate_start);
    }

    if (kcount != 0) {
        *keyCount = kcount;
        void *tmp = (uint64_t *)realloc(keys, *keyCount * sizeof(uint64_t));
        if (tmp == NULL) {
            *keyCount = 0;
            free(keys);
            return NULL;
        }
        keys = (uint64_t *)tmp;
    } else {
        *keyCount = 0;
        free(keys);
        return NULL;
    }

    // 统计重复次数最多的候选密钥，最多输出 TRY_KEYS 个
    countKeys *ck = uniqsort(keys, *keyCount);
    free(keys);
    keys = (uint64_t *)NULL;
    *keyCount = 0;

    if (ck != NULL) {
        for (i = 0; i < TRY_KEYS; i++) {
            if (ck[i].count > 0) {
                *keyCount += 1;
                void *tmp = realloc(keys, sizeof(uint64_t) * (*keyCount));
                if (tmp != NULL) {
                    keys = (uint64_t *)tmp;
                    keys[*keyCount - 1] = ck[i].key;
                } else {
                    free(keys);
                    keys = NULL;
                    break;
                }
            }
        }
        free(ck);
    }
    return keys;
}

// Return 1 if the nonce is invalid else return 0
uint8_t valid_nonce(uint32_t Nt, uint32_t NtEnc, uint32_t Ks1, uint8_t *parity) {
    return (
               (oddparity8((Nt >> 24) & 0xFF) == ((parity[0]) ^ oddparity8((NtEnc >> 24) & 0xFF) ^ BIT(Ks1, 16))) && \
               (oddparity8((Nt >> 16) & 0xFF) == ((parity[1]) ^ oddparity8((NtEnc >> 16) & 0xFF) ^ BIT(Ks1, 8))) && \
               (oddparity8((Nt >> 8) & 0xFF) == ((parity[2]) ^ oddparity8((NtEnc >> 8) & 0xFF) ^ BIT(Ks1, 0)))
           ) ? 1 : 0;
}
