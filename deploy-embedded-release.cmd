cd nucleus
call:deploy
cd ..\appserver
call:deploy
cd extras
call:deploy
cd embedded\all
rem and final deploy....
:deploy
call mvn -N clean deploy -DaltDeploymentRepository=icon-release::default::http://s030a1123.rwe.com/nexus/content/repositories/releases %*
goto:eof