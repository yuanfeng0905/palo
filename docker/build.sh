#!/usr/bin/env bash


set -e

TOP_DIR=$(pwd)/../

# 构建docker镜像，这里先下载第三方依赖
cd ../thirdparty
mkdir -p src
mkdir -p download 

cd download
THIRDPARTY_URL="http://palo-opensource.gz.bcebos.com/palo-thirdparty.tar.gz?authorization=bce-auth-v1%2F069fc2786e464e63a5f1183824ddb522%2F2017-08-11T13%3A18%3A14Z%2F-1%2Fhost%2Fec3d7693a3ab4fe76fb23f8e77dff40624bde867cab75d3842e719816cbd1d2b"
PALO_THIRDPARTY_LIB="palo-thirdparty.tar.gz"
if ! [ -f "$PALO_THIRDPARTY_LIB" ]; then
    curl -fSL $THIRDPARTY_URL -o $PALO_THIRDPARTY_LIB
fi
tar -zxvf $PALO_THIRDPARTY_LIB --strip-components 1 -C ../src #去掉src目录层次

# 下载jdk1.8支持
JDK_URL="http://download.oracle.com/otn-pub/java/jdk/8u171-b11/512cd62ec5174c3487ac17c61aaa89e8/jdk-8u171-linux-x64.tar.gz"
JDK_LIB="jdk_1.8.tar.gz"
if ! [ -f "$JDK_LIB" ]; then
    curl -fSL $JDK_URL \
    -H "Cookie: oraclelicense=accept-securebackup-cookie" \
    -H "Connection: keep-alive" \
    -o $JDK_LIB
fi
tar zxvf $JDK_LIB

# 编译第三方依赖库
echo "start compile thirdparty libs..."
cd $TOP_DIR/thirdparty
./build-thirdparty.sh
echo "--ok--"

# 构建
echo "fe&be building..."
cd $TOP_DIR
./build.sh clean
./build.sh
echo "--ok--"
exit 0
# 构建docker images
cd $TOP_DIR/docker
echo "build docker image for BE..."
docker build -f be/Dockerfile
echo "--ok--"
echo "build docker image for FE..."
docker build -f fe/Dockerfile
echo "--ok--"