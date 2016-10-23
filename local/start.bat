@echo off
java -Dlogback.configurationFile=logback.xml -jar ..\target\app-runner-router-1.1-SNAPSHOT.jar config.properties
