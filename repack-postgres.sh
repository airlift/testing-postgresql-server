#!/bin/bash

set -eu

# forked from https://github.com/opentable/otj-pg-embedded

# https://www.enterprisedb.com/products-services-training/pgbindownload

VERSION=9.6.3-1
BASEURL="https://get.enterprisedb.com/postgresql"

TAR=tar
test -x /usr/local/bin/gtar && TAR=/usr/local/bin/gtar

if ! $TAR --version | grep -q "GNU tar"
then
    echo "GNU tar is required."
    echo "Hint: brew install gnu-tar"
    $TAR --version
    exit 100
fi

set -x

cd $(dirname $0)

RESOURCES=target/generated-resources

mkdir -p dist $RESOURCES

LINUX_NAME=postgresql-$VERSION-linux-x64-binaries.tar.gz
LINUX_DIST=dist/$LINUX_NAME

OSX_NAME=postgresql-$VERSION-osx-binaries.zip
OSX_DIST=dist/$OSX_NAME

WINDOWS_NAME=postgresql-$VERSION-windows-x64-binaries.zip
WINDOWS_DIST=dist/$WINDOWS_NAME

test -e $LINUX_DIST || curl -o $LINUX_DIST "$BASEURL/$LINUX_NAME"
test -e $OSX_DIST || curl -o $OSX_DIST "$BASEURL/$OSX_NAME"
test -e $WINDOWS_DIST || curl -o $WINDOWS_DIST "$BASEURL/$WINDOWS_NAME"

PACKDIR=$(mktemp -d "${TMPDIR:-/tmp}/pg.XXXXXXXXXX")
tar -xzf $LINUX_DIST -C $PACKDIR
pushd $PACKDIR/pgsql
$TAR -czf $OLDPWD/$RESOURCES/postgresql-Linux-amd64.tar.gz \
  share/postgresql \
  lib \
  bin/initdb \
  bin/pg_ctl \
  bin/postgres
popd
rm -rf $PACKDIR

PACKDIR=$(mktemp -d "${TMPDIR:-/tmp}/pg.XXXXXXXXXX")
unzip -q -d $PACKDIR $OSX_DIST
pushd $PACKDIR/pgsql
$TAR -czf $OLDPWD/$RESOURCES/postgresql-Mac_OS_X-x86_64.tar.gz \
  share/postgresql \
  lib/libiconv.2.dylib \
  lib/libxml2.2.dylib \
  lib/libssl.1.0.0.dylib \
  lib/libcrypto.1.0.0.dylib \
  lib/libuuid.1.1.dylib \
  lib/postgresql/*.so \
  bin/initdb \
  bin/pg_ctl \
  bin/postgres
popd
rm -rf $PACKDIR

PACKDIR=$(mktemp -d "${TMPDIR:-/tmp}/pg.XXXXXXXXXX")
unzip -q -d $PACKDIR $WINDOWS_DIST
pushd $PACKDIR/pgsql
$TAR -czf $OLDPWD/$RESOURCES/postgresql-Windows_10-amd64.tar.gz \
  share \
  lib/iconv.lib \
  lib/libxml2.lib \
  lib/ssleay32.lib \
  lib/ssleay32MD.lib \
  lib/*.dll \
  bin/initdb.exe \
  bin/pg_ctl.exe \
  bin/postgres.exe \
  bin/*.dll
popd

rm -rf $PACKDIR