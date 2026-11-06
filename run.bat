@echo off
rem 启动本地 FCM 推送服务：没有打包过就先打包，然后打开 http://127.0.0.1:8090
rem 可选：先 set FCM_CREDENTIALS=私钥路径，启动时自动加载
chcp 65001 >nul
cd /d %~dp0
if not exist target\fcm-push-1.0.0.jar (
    echo 首次运行，正在打包...
    call mvn -q -DskipTests package || (echo 打包失败，确认已安装 JDK 17 和 Maven & pause & exit /b 1)
)
start "" http://127.0.0.1:8090
java -Dfile.encoding=UTF-8 -jar target\fcm-push-1.0.0.jar
