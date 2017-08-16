#!/bin/bash


# Linux
export JAVA_HOME=/home/marty/work/programs/jdk-9-ea
export PATH=${JAVA_HOME}/bin:/home/marty/work/programs/apache-maven-3.5.0/bin:$PATH

# Win
#export JAVA_HOME=/c/Work/Programs/jdk-9.178
#export PATH=${JAVA_HOME}/bin:/c/Work/Programs/apache-maven-3.5.0/bin:$PATH

java -version

#mvn -Dmaven.javadoc.skip=true -Dtest=net.openhft.chronicle.bytes.ByteStoreTest#testWriteReadUtf8 clean install test
# mvn -Dmaven.javadoc.skip=true -Dtest=net.openhft.chronicle.bytes.ByteStringParserTest#testWriteBytes clean install test
#mvn -Dmaven.javadoc.skip=true -Dtest=net.openhft.chronicle.bytes.ByteStringParserTest#testAppendParseUTF clean install test
# mvn -Dmaven.javadoc.skip=true -DtrimStackTrace=false -Dtest=net.openhft.chronicle.bytes.ByteStringParserTest#testAppendParse clean install test
# mvn -Dmaven.javadoc.skip=true -DtrimStackTrace=false -Dsurefire.useFile=false -Dtest=net.openhft.chronicle.bytes.ByteStringParserTest#testAppendSubstring clean install test
#mvn -Dmaven.javadoc.skip=true -DtrimStackTrace=false -Dsurefire.useFile=false -Dtest=net.openhft.chronicle.bytes.BytesMarshallableTest clean install test
# mvn -Dmaven.javadoc.skip=true -Dsurefire.useFile=false -Dtest=net.openhft.chronicle.bytes.BytesTest#testPartialWriteBB clean install test
# mvn -Dmaven.javadoc.skip=true -DtrimStackTrace=false -Dsurefire.useFile=false -Dtest=net.openhft.chronicle.bytes.MappedMemoryTest#mappedMemoryTest clean install test
#mvn -Dmaven.javadoc.skip=true -DtrimStackTrace=false -Dsurefire.useFile=false -Dtest=net.openhft.chronicle.bytes.BytesTest clean test
#mvn -Dmaven.javadoc.skip=true -DtrimStackTrace=false -Dsurefire.useFile=false -Dtest=net.openhft.chronicle.bytes.MappedMemoryTest clean install
# mvn -Dmaven.javadoc.skip=true -DtrimStackTrace=false -Dsurefire.useFile=false -Dtest=net.openhft.chronicle.bytes.MappedFileTest clean test
#mvn -Dmaven.javadoc.skip=true -DtrimStackTrace=false -Dsurefire.useFile=false -Dtest=net.openhft.chronicle.bytes.ByteStoreTest#testWriteReadUtf8 clean test

# with tests
mvn -Dmaven.javadoc.skip=true -Dsurefire.useFile=false -DtrimStackTrace=false clean install

# without tests
# mvn -Dmaven.javadoc.skip=true -Dmaven.test.skip=true -Dsurefire.useFile=false -DtrimStackTrace=false clean install