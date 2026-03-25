# QuestionOS 部署：零基础分步手册

面向：**阿里云轻量应用服务器**、系统 **Alibaba Cloud Linux 3**、公网 IP 示例 **`47.253.98.164`**（请按需改成你的 IP）。

你需要两样东西：

1. **你自己的电脑**（下面按 macOS 写；Windows 见文末说明）  
2. **已能 SSH 登录的服务器**（阿里云控制台有「远程连接」或你用终端 `ssh root@你的IP`）

---

## 第 0 步：先搞懂「要干什么」

- 你的电脑上：把项目**编译**成「后端一个 jar 包 + 前端一堆文件」，再打成一个 **`.tar.gz`**。  
- 服务器上：安装 **Java / Node / Nginx**，把压缩包解开，用 **systemd** 常驻跑后端和前端，用 **Nginx** 把 **80 端口**分给网页和接口。  
- 浏览器访问：`http://你的公网IP`。

全程大约 **30～60 分钟**（首次下载依赖会久一点）。

---

## 第 1 步：在你电脑上安装「打包工具」

### 1.1 打开「终端」

- Mac：**聚焦搜索**里输入 `终端` 或 `Terminal`。

### 1.2 检查是否已有 Java / Maven / Node

在终端里**逐行**输入下面命令，每行回车：

```bash
java -version
mvn -version
node -v
```

**希望看到：**

- Java：**21**（显示 `21.x.x` 即可）  
- Maven：有版本号输出  
- Node：**v20** 或更高（如 `v20.x.x`）

### 1.3 缺什么装什么（Mac 常用方式）

若未安装 **Homebrew**，先打开 [https://brew.sh](https://brew.sh) 按官网一行命令安装。

然后：

```bash
brew install openjdk@21 maven node@20
```

把 Java 21 加到 PATH（安装完成后终端里会提示类似 `echo 'export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"' >> ~/.zshrc`，照做后执行 `source ~/.zshrc`）。

再执行一次第 1.2 步，确认三条命令都有输出。

### 1.4 进入项目根目录

你的 QuestionOS 代码放在哪，就 `cd` 到哪。例如：

```bash
cd "/Users/你的用户名/Documents/项目文件/QuestionOS"
```

用下面命令确认能看到 `java-backend` 和 `v0.2`：

```bash
ls
```

应能看到 `java-backend`、`v0.2` 等文件夹。

---

## 第 2 步：想一个「生产环境密码」（Sandbox Token）

这是前后端共用的鉴权串，**不要用简单密码**，建议 **20 位以上字母数字混合**，自己记在密码管理器里。

下面用 **`我的长随机token`** 举例，请换成你自己的。

**重要：**打包时用的 token，和服务器上配置文件里的 token，**必须完全一致**。

---

## 第 3 步：在你电脑上执行「打包脚本」

仍在**项目根目录**：

```bash
chmod +x deploy/alinux/pack-release.sh
```

设置环境变量（**把 IP 和 token 改成你的**）：

```bash
export PUBLIC_API_URL=http://47.253.98.164
export SANDBOX_TOKEN='我的长随机token'
```

执行打包（会跑 Maven 和 npm，**可能要几分钟**）：

```bash
./deploy/alinux/pack-release.sh
```

**成功时**最后一行类似：

```text
==> OK: .../dist/questionos-release-20250325-1530.tar.gz
```

**若失败：**

- 提示 `mvn: command not found` → 回到第 1 步装 Maven。  
- 提示 `npm: command not found` → 装 Node。  
- Java 编译报错 → 把完整报错复制下来再排查。

确认文件存在：

```bash
ls -la dist/
```

---

## 第 4 步：把压缩包传到服务器

### 4.1 确认你能 SSH

在**你自己电脑**终端执行（root 改成你的登录用户，IP 改成你的）：

```bash
ssh root@47.253.98.164
```

第一次会问 `yes/no`，输入 `yes`。然后输入密码（阿里云控制台可重置密码）。

能登录后出现类似 `[root@xxx ~]#` 就说明 OK。输入 `exit` 先退出。

### 4.2 用 scp 上传

在**你自己电脑**上（不要登录在服务器里执行），项目根目录下：

```bash
scp dist/questionos-release-*.tar.gz root@47.253.98.164:/tmp/
```

输入密码，等待上传完成。

---

## 第 5 步：登录服务器，安装系统软件（只做一次）

再次 SSH 登录：

```bash
ssh root@47.253.98.164
```

### 5.1 安装 Nginx、Java 21、Git

```bash
sudo dnf install -y nginx java-21-openjdk-headless git
```

### 5.2 安装 Node.js 20

```bash
curl -fsSL https://rpm.nodesource.com/setup_20.x | sudo bash -
sudo dnf install -y nodejs
```

验证：

```bash
node -v
java -version
nginx -v
```

### 5.3 启动 Nginx 并设置开机自启

```bash
sudo systemctl enable --now nginx
```

### 5.4（建议）关掉默认站点，避免和 QuestionOS 抢 80 端口

```bash
sudo mv /etc/nginx/conf.d/default.conf /etc/nginx/conf.d/default.conf.bak 2>/dev/null || true
sudo nginx -t && sudo systemctl reload nginx
```

### 5.5 防火墙放行 80 端口

- **阿里云轻量控制台**：服务器 → **防火墙** → 添加规则：**TCP 80**。  
- 若服务器里开了 `firewalld`，可执行：

```bash
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --reload
```

---

## 第 6 步：解压并运行安装脚本

在服务器上（已 SSH 为 root）：

```bash
cd /tmp
ls questionos-release-*.tar.gz
```

解压（文件名按你实际上传的改，可用 `Tab` 补全）：

```bash
tar xzf questionos-release-*.tar.gz
```

解压后**当前目录**下应有：`java-backend-0.1.0.jar`、`frontend`、`deploy`。**没有**名叫 `questionos-release` 的子文件夹，这是正常的。

赋权并执行安装：

```bash
sudo chmod +x deploy/alinux/install-server.sh
sudo ./deploy/alinux/install-server.sh
```

脚本会：

- 创建用户 `questionos`，把程序放到 **`/opt/questionos`**  
- 在服务器上执行 `npm ci` 安装前端依赖（**要联网，可能较久**）  
- 若 **`/etc/questionos/backend.env` 尚不存在**，会从模板复制一份

**若报错：**

- `nginx -t 失败` → 看提示是否还有别的配置占用了 80；可先备份其它 `conf.d` 下的站点再 `nginx -t`。  
- `npm ci` 失败 → 检查网络、DNS，或重试 `sudo -u questionos bash -c 'cd /opt/questionos/frontend && npm ci --omit=dev'`。

---

## 第 7 步：配置后端环境变量（必做）

首次安装会生成 **`/etc/questionos/backend.env`**，里面的 token 还是占位符，**必须改成你在第 2 步设的那个**，与打包时 `SANDBOX_TOKEN` **一致**。

```bash
sudo nano /etc/questionos/backend.env
```

你会看到类似：

```env
QUESTIONOS_AUTH_SANDBOX_TOKEN=REPLACE_ME_SANDBOX_TOKEN
QUESTIONOS_CORS_ALLOWED_ORIGINS=http://47.253.98.164,http://localhost:3000,http://127.0.0.1:3000
```

把第一行改成（示例）：

```env
QUESTIONOS_AUTH_SANDBOX_TOKEN=我的长随机token
```

**不要加引号**（除非你的 token 里本身有特殊字符，再另说）。

第二行里的 IP 若与你公网不一致，改成你的 **`http://你的IP`**。

保存退出：

- **nano**：按 `Ctrl+O` 回车保存，`Ctrl+X` 退出。

然后重启服务：

```bash
sudo systemctl restart questionos-backend
```

（若你改 env 前前端已起过，也可一并重启：`sudo systemctl restart questionos-backend questionos-frontend`）

---

## 第 8 步：确认服务是否在跑

```bash
sudo systemctl status questionos-backend questionos-frontend nginx --no-pager
```

三个都应是 **active (running)**。若有 **failed**，看详情：

```bash
sudo journalctl -u questionos-backend -n 50 --no-pager
sudo journalctl -u questionos-frontend -n 50 --no-pager
```

本机测后端健康（把 `我的长随机token` 换成你的）：

```bash
curl -s -H "Authorization: Bearer 我的长随机token" http://127.0.0.1:8080/actuator/health
```

若返回里带 `"status":"UP"` 说明后端正常。

---

## 第 9 步：用浏览器访问

在电脑浏览器打开：

```text
http://47.253.98.164
```

（换成你的 IP）

能打开页面后，进沙盘/咨询相关页面测试；若接口 401，多半是 **token 与 backend.env / 打包时不一致**，回到第 2、3、7 步对齐后重新打包或改配置并重启后端。

---

## 第 10 步：以后更新版本怎么做（推荐：直接重新运行安装脚本）

为了支持历史版本备份与一键回滚，后续你每次部署新包时，都按下面做，而不是手工复制 jar/前端：

1. 在你电脑上改代码后，重新执行第 3 步 `pack-release.sh`（生成新的 `tar.gz`）。  
2. `scp` 新包到服务器 `/tmp/`，解压到临时目录。  
3. 在服务器里进入解压目录，执行安装脚本：

```bash
sudo chmod +x deploy/alinux/install-server.sh
sudo ./deploy/alinux/install-server.sh
```

这一步会自动：

- 停止服务
- 把当前正在运行的版本备份到 `/opt/questionos/releases/<时间戳>/`
- 覆盖安装新版本并启动服务

---

### 第 11 步：线上出问题怎么回滚

如果新版本部署后页面异常/接口异常，直接回滚到“上一版”：

```bash
sudo /opt/questionos/rollback.sh
```

如果你想回滚到更早的某次备份：

```bash
ls -1 /opt/questionos/releases
sudo /opt/questionos/rollback.sh 20250325-153012
```

---

## Windows 用户说明

- **SSH / scp**：可安装 [Windows Terminal](https://aka.ms/terminal)，或用 **PuTTY**、**WinSCP**。  
- **打包**：建议在项目目录用 **WSL2（Ubuntu）** 装 JDK 21、Maven、Node 20，在 WSL 里执行与 Mac 相同的打包命令；或在 Windows 上分别安装这三样后，在 **PowerShell** 里执行 `pack-release.sh` 需先装 **Git Bash** 或 WSL。

---

## 对照检查清单（可打印）

- [ ] 本机 `java -version` 为 21  
- [ ] 本机 `mvn -version`、`node -v` 正常  
- [ ] `PUBLIC_API_URL` 为 `http://公网IP`（无多余斜杠）  
- [ ] `SANDBOX_TOKEN` 与 `backend.env` 里 **完全一致**  
- [ ] `scp` 上传成功  
- [ ] 服务器已装 nginx、java-21、nodejs  
- [ ] 防火墙放行 80  
- [ ] 已执行 `install-server.sh`  
- [ ] 已编辑 `/etc/questionos/backend.env` 并 `restart questionos-backend`  
- [ ] 浏览器能打开 `http://公网IP`

更短的命令速查仍见同目录 **`README.md`**。
