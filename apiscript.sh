#! /usr/bin/env bash

if [ "$#" = "0" ] ; then
    >&2 echo "usage: $(basename $0) SCRIPT ARGS"
    exit 1
fi

groovy -cp "$(dirname "$0")/lib" $* |& grep -v _JAVA_OPTIONS
