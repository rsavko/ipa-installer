#! /bin/sh

SESSION_ID=`pwd | sed 's/\//_/g'`
echo $SESSION_ID > session.id

sudo screen -S $SESSION_ID -md java -Xms256M -Xmx2048M \
       -jar manifestgenerator-1.0-SNAPSHOT-fat.jar -conf conf.json 

# Wait for screen to run the child java process.
sleep 2

SCREENPID=`ps -ef | grep $SESSION_ID | grep -v grep | awk {'print $2'}`
echo $SCREENPID > screen.pid
echo `ps -ef | grep $SCREENPID | grep -v grep | grep -v SCREEN | awk {'print $2'}`  > live.pid
