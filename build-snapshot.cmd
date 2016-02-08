call mvn clean install -DskipTests %*
cd appserver\extras\embedded\all
call mvn install -DskipTests
cd ../../../..