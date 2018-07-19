#!/usr/bin/env bash

set -e

TOP_DIR=$(pwd)/../
from_source=$1
ver=$2
PALO_RELEASE_VER="palo-"$ver
PALO_RELEASE_URL=""

if [ "$from_source" = "" ] || [ "$ver" = "" ]; then
    echo "=== ERR: please attach from_source and version params";
    exit 1
fi

if [ "$ver" = "0.8.0" ]; then
    PALO_RELEASE_URL="http://palo-opensource.gz.bcebos.com/palo-0.8.0-release-20180323.tar.gz?authorization=bce-auth-v1%2F069fc2786e464e63a5f1183824ddb522%2F2018-03-23T07%3A47%3A15Z%2F-1%2Fhost%2Fb3655151fa1f8b52100ea8a987f3de8743a1cdc84e50220482f7e46f6f5ec34c"
fi
if [ "$ver" = "0.8.1" ]; then
    PALO_RELEASE_URL="http://palo-opensource.gz.bcebos.com/palo-0.8.1-release-20180418.tar.gz?authorization=bce-auth-v1%2F069fc2786e464e63a5f1183824ddb522%2F2018-04-18T05%3A11%3A34Z%2F-1%2Fhost%2F9dabe5cb37dd5fa253b41075f360c1c4389fa4965ccd59cb7e774d3c1b099f9f"
fi

# 如果从源码构建，先下载第三方依赖
if [ "$from_source" = "source" ]; then
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

    mkdir -p $PALO_RELEASE_VER

    # 如果没有指定下载版本，从本地编译目录copy到这里
    if [ "$PALO_RELEASE_URL" = "" ]; then
        cp -r $TOP_DIR/output/* $PALO_RELEASE_VER/
    else
        # 直接下载github上release binary file, 然后解压
        if ! [ -f "$PALO_RELEASE_VER.tar.gz" ]; then
            curl -fSL $PALO_RELEASE_URL -o $PALO_RELEASE_VER.tar.gz
        fi
        tar zxvf $PALO_RELEASE_VER.tar.gz -C $PALO_RELEASE_VER --strip-components 1
    fi
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

# 针对官方启动脚本打补丁，兼容docker
cd $TOP_DIR/docker
#diff download/$PALO_RELEASE_VER/fe/bin/start_fe.sh patchs/start_fe.sh >diff_fe
#diff download/$PALO_RELEASE_VER/be/bin/start_be.sh patchs/start_be.sh >diff_be
patch download/$PALO_RELEASE_VER/fe/bin/start_fe.sh < patchs/start_fe.patch
patch download/$PALO_RELEASE_VER/be/bin/start_be.sh < patchs/start_be.patch


# 构建docker images
cd $TOP_DIR/docker
echo "=== BE image building..."
docker build --build-arg version=$ver -f Dockerfile-be -t palo/palo-be:$ver .
echo "=== ok"
echo "=== FE image building..."
docker build --build-arg version=$ver -f Dockerfile-fe -t palo/palo-fe:$ver .
echo "=== ok"