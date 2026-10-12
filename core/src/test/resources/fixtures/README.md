# 跨实现 fixture（T7 / F6.1）

这组文件回答一个问题：**同一份密文仓库，换一个平台能不能解开、能不能读出同一个明文。**
v1 没有 iOS App，所以这是「格式可移植」这条约束在 v1 唯一能被验证的方式。

## 内容

| 文件 | 说明 |
|---|---|
| `vector.properties` | 全部期望值：密码、盐、KDF 参数、**主密钥**、明文 SHA-256 |
| `vault/vault.meta` | 37 字节明文边车（含上面的盐与参数） |
| `vault/vault.meta.bak` | 与主文件逐字节相同 |
| `vault/data/00000000-0000-4000-8000-000000000001.bin` | 密文容器，明文长 1,048,561 字节（跨 2 块、末块变长） |

## 信任来源

`vector.properties` 里的 `master.key.hex` **由 Bouncy Castle（纯 Java 第二实现）算出**，
不是由被测的 `argon2-jvm` 算出。测试用 `argon2-jvm` 重新派生并逐字节比对——
不一致就说明两个实现对 Argon2id 的理解不同，而「换平台能解」这个承诺当场破产。

## 复算步骤

### 固定头（81 字节；所有多字节整数**大端序**）

| 偏移 | 宽度 | 字段 |
|---|---|---|
| 0 | 4 | `magic` = `AVLT`（字节 `41 56 4C 54`） |
| 4 | 1 | `version` = `1` |
| 5 | 60 | `wrapped_file_key` = `nonce(12) ‖ ct(32) ‖ tag(16)` |
| 65 | 4 | `base_nonce` |
| 69 | 8 | `plaintext_len` |
| 77 | 4 | `metadata_len` |

`header_tag(16)` 紧跟在固定头之后（偏移 81）。`metadata_len` 被 `header_tag` 认证，
本 fixture 恒为 1024，但读者 MUST 从文件头读它，不许硬编码。
`plaintext_len` **可能**在 commit 阶段被修正过（写入时声明的长度允许不准），
所以明文长度一律取**头部**里的值，不要相信任何外部记录。

### 派生主密钥

1. 对 `password` 的 Unicode **NFC** 形式做 UTF-8 编码。
2. Argon2id（版本 1.3 / `0x13`），`m = kdf.m` KiB，`t = kdf.t`，`p = kdf.p`，
   盐 = `kdf.salt.hex`，输出 32 字节 → `master.key.hex`。

### 解容器

3. 用主密钥 AES-256-GCM 解 `wrapped_file_key`（AAD 为空）得到该文件的 data key。
4. `nonce(12)` = `base_nonce(4) ‖ counter(8)`，counter 为大端序。各 counter 的用途与 AAD：

   | counter | 用途 | AAD |
   |---|---|---|
   | 0 | `header_tag` | 固定头 81 字节 |
   | 1 | metadata 区（`metadata_len` 字节密文 + 16 字节 tag） | 固定头 81 字节 |
   | i+2 | 第 i 个数据块 | **空**（数据块不带 AAD） |

   `header_tag` 与 `metadata_tag` 的 AAD 是**提交时写在盘上**的那 81 字节：
   若 commit 修正过 `plaintext_len`，头部已被重写，所以要先读盘、再用这份字节当 AAD。
5. metadata 区的明文结构（未用部分零填充到 `metadata_len`）：

   `real_len(2) ‖ name_len(2) ‖ display_name(NFC UTF-8) ‖ duration_ms(8) ‖ sha256(32) ‖ flags(1)`

   `real_len = 45 + name_len`（45 = 2+2+8+32+1）；`flags` MUST 为 0。
   未提交判定有两条，**以 `metadata_tag` 为准，且 MUST 先判**：磁盘上 `metadata_tag` 全为 0 ⇒ 未提交（**无需密码**）。解出的 `real_len == 0` **只在占位态、已提交态两个稳定态里与之等价**；在「commit 正文已写、tag 未写」的撕裂窗口里，metadata 与 tag 连续读入后 GCM 校验失败、`real_len` **根本读不出来**，此时**只有全零 tag 可判**。所以读取器 MUST 在**解包密钥 / 校验 `header_tag` 之前**先查 `metadata_tag`——否则会把撕裂文件误当成已提交。
   *（2026-10-12 更正：原文写「`metadata_tag` 全为 0，或解出的 `real_len == 0`，都表示未提交」，用「或」把两条判据说成可互换，会诱使实现把 `real_len == 0` 当主判据。）*

### 拼明文

6. 数据区起点 = `113 + metadata_len`；明文块 `P = 1_048_560`，磁盘步长 `1_048_576`，末块变长。
7. 拼出的明文 SHA-256 必须等于 `plaintext.sha256.hex`，长度等于 `plaintext.length`。

## 重新生成

```bash
cd core && ../gradlew generateFixtures
```
改 `FixtureSpec` 中任何一个常量都必须重新生成并连同密文一起提交。
