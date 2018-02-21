@echo off
java -Dlogback.configurationFile=logback.xml -jar ..\target\app-runner-router-1.7-SNAPSHOT.jar config.properties
