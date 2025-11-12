FROM eclipse-temurin:17-jdk

WORKDIR /app

# Create directories
RUN mkdir -p /app/src/main/java/com/taskscheduler /app/web /app/classes

# Copy Java source files
COPY src/main/java/com/taskscheduler/*.java /app/src/main/java/com/taskscheduler/

# Copy web files
COPY web /app/web

# Compile Java files
RUN javac -d /app/classes -sourcepath /app/src/main/java /app/src/main/java/com/taskscheduler/TaskScheduler.java

# Verify compilation
RUN ls -la /app/classes/com/taskscheduler/

# Expose port
EXPOSE 8080

# Run the application
CMD ["java", "-cp", "/app/classes", "com.taskscheduler.TaskScheduler"]

