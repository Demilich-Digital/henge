# Build the jar first: ./gradlew :examples:shop-app:bootJar
FROM eclipse-temurin:21-jre
COPY examples/shop-app/build/libs/shop-app-*.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
