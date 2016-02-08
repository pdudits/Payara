cd nucleus
call:deploy
cd ..\appserver
call:deploy
cd extras
call:deploy
cd embedded\all
rem and final deploy....
:deploy
call mvn -N deploy -DaltDeploymentRepository=icon-snapshot::default::http://s030a1123.rwe.com/nexus/content/repositories/snapshots %*
goto:eof