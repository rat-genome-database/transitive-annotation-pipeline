#!/usr/bin/env bash
# shell script to run Transitive Annotation pipeline
. /etc/profile

APPNAME=TransitiveAnnotPipeline
APPDIR=/home/rgddata/pipelines/$APPNAME
SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`

cd $APPDIR

java -Dspring.config=$APPDIR/../properties/default_db2.xml \
    -Dlog4j.configuration=file://$APPDIR/properties/log4j.properties \
    -jar lib/${APPNAME}.jar "$@" > $APPDIR/run.log 2>&1

mailx -s "[$SERVER] Transitive Annot pipeline ok" mtutaj@mcw.edu < $APPDIR/logs/summary.log
