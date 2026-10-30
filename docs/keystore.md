# 签名密钥与 APK 签名（keystore）

## 密钥文件位置（本地，不提交仓库）

- keystore：`/home/WXM/AllProjects/.keystores/DiscordAutoTranslate/discordautotranslate-release.jks`
- 密码文件：`/home/WXM/AllProjects/.keystores/DiscordAutoTranslate/discordautotranslate-keystore.properties`
  - 键：`storeFile` / `storePassword` / `keyAlias` / `keyPassword`
  - PKCS12 格式要求 store 与 key 口令一致，两值相同。
- 两文件权限均为 `600`；目录 `.keystores` 在仓库之外，且 `*.jks` /
  `*-keystore.properties` 已列入 `.gitignore` 双重保护。

## GitHub Actions 签名

构建时由工作流 `.github/workflows/build.yml` 的 **Inject signing keystore**
步骤注入。需要配置仓库级 Secrets（Settings → Secrets and variables →
Actions）：

| Secret | 值 |
|---|---|
| `KEYSTORE_BASE64` | `discordautotranslate-release.jks` 的 base64（`base64 -w0 <jks>`） |
| `KEYSTORE_PASSWORD` | `discordautotranslate-keystore.properties` 中的 `storePassword` |
| `KEY_ALIAS` | `discordautotranslate` |

工作流行为：

- 未配置 `KEYSTORE_BASE64` 时输出 `configured=false`，**跳过签名**，
  Debug 构建使用 AGP 默认调试签名，构建仍通过；
- 配置后 Release 与 Debug 均使用该 keystore 签名，构建末尾
  **Verify APK signature** 步骤用 `apksigner verify --print-certs` 校验；
- keystore 在工作流内解码到 `$RUNNER_TEMP/keystore/`，仅存活于本次运行，
  不落地仓库、不进日志。

## 发布与模块仓库同步

`.github/workflows/release.yml` 手动触发，构建、签名并发布 APK：

- `stable`：固定从 `main` 构建，发布正式版；构建前要求配置
  `LSPOSED_REPO_TOKEN`。
- `prerelease`：从指定分支或 commit SHA 构建，只发布到源码仓库，
  不推送到 LSPosed 模块仓库。

正式版 Release 发布成功后，同一 tag 和同名 APK 会自动同步到
`Xposed-Modules-Repo/io.github.wxmyyds.discordautotranslate`。需要在该源码仓库
配置仓库级 Secret（Settings → Secrets and variables → Actions）：

| Secret | 值 |
|---|---|
| `LSPOSED_REPO_TOKEN` | 对模块仓库具有 `Contents: Read and write` 权限的 GitHub token |

Token 仅由发布工作流读取，用于创建或更新目标仓库 Release；不写入仓库，也不输出到日志。
若目标仓库已有同 tag Release，会更新标题和说明，并以 `--clobber` 替换 APK。

## build.gradle 读取方式

`app/build.gradle` 只从环境变量 `KEYSTORE_PROPERTIES` 指向的 properties
文件读取签名参数（密钥路径相对该文件所在目录解析）；未注入或文件不存在
时不配置签名。GitHub Actions 由工作流设置该环境变量，本地不使用。

## 已生成的密钥信息（2026-10-30）

- 格式：PKCS12（keytool 默认），RSA 2048，SHA384withRSA，有效期 10950 天
- alias：`discordautotranslate`
- 证书指纹 SHA-256：
  `B4:E8:18:85:3F:B2:DB:D0:AD:58:FA:43:87:3D:D6:B5:CD:41:D3:01:F7:56:0A:E4:63:D9:FC:AB:72:39:96:23`

## 风险提示

- `applicationId` 已改为 `io.github.wxmyyds.discordautotranslate`：
  已安装旧包名版本的用户需卸载重装（签名变更同理），无法覆盖升级。
- keystore 一旦丢失无法重新生成相同的证书，旧版本安装将无法升级；
  请自行离线备份 `.keystores/DiscordAutoTranslate/` 目录。