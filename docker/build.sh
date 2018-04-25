#!/usr/bin/env bash


set -e

TOP_DIR=$(pwd)/../
from_source=$1
# 如果从源码构建，先下载第三方依赖
if [ $from_source = "source" ]; then
    echo "=== build from palo source..."
    cd $TOP_DIR/thirdparty
    mkdir -p src
    mkdir -p download 

    cd download
    THIRDPARTY_URL="http://palo-opensource.gz.bcebos.com/palo-thirdparty.tar.gz?authorization=bce-auth-v1%2F069fc2786e464e63a5f1183824ddb522%2F2017-08-11T13%3A18%3A14Z%2F-1%2Fhost%2Fec3d7693a3ab4fe76fb23f8e77dff40624bde867cab75d3842e719816cbd1d2b"
    PALO_THIRDPARTY_LIB="palo-thirdparty.tar.gz"
    if ! [ -f "$PALO_THIRDPARTY_LIB" ]; then
        curl -fSL $THIRDPARTY_URL -o $PALO_THIRDPARTY_LIB
    fi
    tar -zxvf $PALO_THIRDPARTY_LIB --strip-components 1 -C ../src #去掉src目录层次

    # 编译第三方依赖库
    echo "start compile thirdparty libs..."
    cd $TOP_DIR/thirdparty
    ./build-thirdparty.sh
    echo "=== ok"

    # 构建
    echo "fe&be building..."
    cd $TOP_DIR
    ./build.sh clean
    ./build.sh
    echo "=== ok"
else
    echo "=== build from palo release binary file."
    mkdir -p download
    cd download
    # 直接下载github上release binary file
    PALO_RELEASE_VER="palo-0.8.1"
    if ! [ -f $PALO_RELEASE_VER.tar.gz ]; then
        PALO_RELEASE_URL="http://palo-opensource.gz.bcebos.com/palo-0.8.1-release-20180418.tar.gz?authorization=bce-auth-v1%2F069fc2786e464e63a5f1183824ddb522%2F2018-04-18T05%3A11%3A34Z%2F-1%2Fhost%2F9dabe5cb37dd5fa253b41075f360c1c4389fa4965ccd59cb7e774d3c1b099f9f"
        curl -fSL $PALO_RELEASE_URL -o $PALO_RELEASE_VER.tar.gz
    fi
    mkdir -p $PALO_RELEASE_VER
    tar zxvf $PALO_RELEASE_VER.tar.gz -C $PALO_RELEASE_VER --strip-components 1
fi


# 下载jdk1.8支持
echo "=== download jdk1.8..."
cd $TOP_DIR/docker/download
JDK_URL="http://download.oracle.com/otn-pub/java/jdk/8u171-b11/512cd62ec5174c3487ac17c61aaa89e8/jdk-8u171-linux-x64.tar.gz"
JDK_LIB="jdk_1.8.tar.gz"
if ! [ -f "$JDK_LIB" ]; then
    curl -fSL $JDK_URL \
    -H "Cookie: oraclelicense=accept-securebackup-cookie" \
    -H "Connection: keep-alive" \
    -o $JDK_LIB
fi
tar zxvf $JDK_LIB

# 构建docker images
cd $TOP_DIR/docker
echo "=== BE image building..."
docker build -f Dockerfile-be .
echo "=== ok"
echo "=== FE image building..."
docker build -f Dockerfile-fe .
echo "=== ok"