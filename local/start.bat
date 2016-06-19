@echo off
java -Dlogback.configurationFile=logback.xml -jar ..\target\app-runner-router-1.0-SNAPSHOT.jar config.properties
