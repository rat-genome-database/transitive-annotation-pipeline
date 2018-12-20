#!/usr/bin/env bash
# shell script to run Transitive Annotation pipeline
. /etc/profile

APPNAME=TransitiveAnnotPipeline
APPDIR=/home/rgddata/pipelines/$APPNAME

cd $APPDIR

java -Dspring.config=$APPDIR/../properties/default_db.xml \
    -Dlog4j.configuration=file://$APPDIR/properties/log4j.properties \
    -jar lib/${APPNAME}.jar "$@"