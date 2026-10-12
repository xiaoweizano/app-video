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

1. 对 `password` 的 Unicode **NFC** 形式做 UTF-8 编码。
2. Argon2id（版本 1.3 / `0x13`），`m = kdf.m` KiB，`t = kdf.t`，`p = kdf.p`，盐 = `kdf.salt.hex`，输出 32 字节 → `master.key.hex`。
3. 用主密钥 AES-256-GCM 解 `vault/data/*.bin` 头部的 `wrapped_file_key(60)`（`nonce(12)+ct(32)+tag(16)`，AAD 为空）得到该文件的 data key。
4. 用 data key 依次校验/解密：`header_tag`(counter 0) → metadata 区(counter 1) → 数据块(counter i+2)。
   三者的 AAD：前两者 = 固定头 81 字节，数据块 = 空。nonce = `base_nonce(4) || counter(8)`。
5. 数据区起点 = `113 + metadata_len`；明文块 `P = 1_048_560`，磁盘步长 `1_048_576`，末块变长。
6. 拼出的明文 SHA-256 必须等于 `plaintext.sha256.hex`，长度等于 `plaintext.length`。

## 重新生成

```bash
cd core && ../gradlew generateFixtures
```
改 `FixtureSpec` 中任何一个常量都必须重新生成并连同密文一起提交。
