#!/bin/bash -eux

# forked from https://github.com/opentable/otj-pg-embedded

# http://www.enterprisedb.com/products-services-training/pgbindownload

VERSION=9.3.5-1
BASEURL="http://get.enterprisedb.com/postgresql"

TAR=tar
test -x /usr/local/bin/gtar && TAR=/usr/local/bin/gtar

$TAR --version | grep -q "GNU tar"
if [ $? -ne 0  ]
then
    echo "GNU tar is required."
    echo "Hint: brew install gnu-tar"
    $TAR --version
    exit 100
fi

cd $(dirname $0)

RESOURCES=target/generated-resources

mkdir -p dist $RESOURCES

LINUX_NAME=postgresql-$VERSION-linux-x64-binaries.tar.gz
LINUX_DIST=dist/$LINUX_NAME

OSX_NAME=postgresql-$VERSION-osx-binaries.zip
OSX_DIST=dist/$OSX_NAME

test -e $LINUX_DIST || curl -o $LINUX_DIST "$BASEURL/$LINUX_NAME"
test -e $OSX_DIST || curl -o $OSX_DIST "$BASEURL/$OSX_NAME"

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
  lib/libuuid.16.dylib \
  lib/postgresql/*.so \
  bin/initdb \
  bin/pg_ctl \
  bin/postgres
popd
rm -rf $PACKDIR
