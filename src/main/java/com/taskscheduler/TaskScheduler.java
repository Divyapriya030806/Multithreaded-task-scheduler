package com.taskscheduler;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;

public class TaskScheduler {
    private static final int PORT = 8080;
    private static final int THREAD_POOL_SIZE = 10;
    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutor;
    private Map<String, ScheduledFuture<?>> scheduledTasks;
    private AtomicInteger taskIdCounter;
    private List<TaskInfo> taskHistory;
    
    public TaskScheduler() {
        this.executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        this.scheduledExecutor = Executors.newScheduledThreadPool(THREAD_POOL_SIZE);
        this.scheduledTasks = new ConcurrentHashMap<>();
        this.taskIdCounter = new AtomicInteger(1);
        this.taskHistory = Collections.synchronizedList(new ArrayList<>());
    }
    
    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        
        server.createContext("/", new StaticHandler());
        server.createContext("/api/schedule", new ScheduleTaskHandler());
        server.createContext("/api/cancel", new CancelTaskHandler());
        server.createContext("/api/tasks", new GetTasksHandler());
        server.createContext("/api/health", new HealthHandler());
        
        server.setExecutor(executorService);
        server.start();
        
        System.out.println("Task Scheduler Server started on port " + PORT);
    }
    
    private class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }
            
            try {
                java.nio.file.Path filePath = Paths.get("web", path);
                if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
                    filePath = Paths.get("web", "index.html");
                }
                
                byte[] content = Files.readAllBytes(filePath);
                String contentType = "text/html";
                if (path.endsWith(".css")) {
                    contentType = "text/css";
                } else if (path.endsWith(".js")) {
                    contentType = "text/javascript";
                }
                
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, content.length);
                OutputStream os = exchange.getResponseBody();
                os.write(content);
                os.close();
            } catch (Exception e) {
                String response = "File not found: " + e.getMessage();
                exchange.sendResponseHeaders(404, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        }
    }
    
    private class ScheduleTaskHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equals("POST")) {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            
            String requestBody = new String(exchange.getRequestBody().readAllBytes());
            Map<String, String> params = parseQueryParams(requestBody);
            
            String taskName = params.getOrDefault("name", "Task " + taskIdCounter.get());
            int delaySeconds = Integer.parseInt(params.getOrDefault("delay", "5"));
            String taskType = params.getOrDefault("type", "simple");
            
            String taskId = "task-" + taskIdCounter.getAndIncrement();
            
            Runnable task = () -> {
                TaskInfo info = new TaskInfo(taskId, taskName, taskType, "RUNNING", new Date());
                taskHistory.add(info);
                System.out.println("Executing task: " + taskName + " (ID: " + taskId + ")");
                
                try {
                    Thread.sleep(2000); // Simulate task execution
                    info.status = "COMPLETED";
                    info.completedAt = new Date();
                    System.out.println("Task completed: " + taskName + " (ID: " + taskId + ")");
                } catch (InterruptedException e) {
                    info.status = "CANCELLED";
                    info.completedAt = new Date();
                    System.out.println("Task cancelled: " + taskName + " (ID: " + taskId + ")");
                }
            };
            
            ScheduledFuture<?> future = scheduledExecutor.schedule(task, delaySeconds, TimeUnit.SECONDS);
            scheduledTasks.put(taskId, future);
            
            TaskInfo info = new TaskInfo(taskId, taskName, taskType, "SCHEDULED", new Date());
            taskHistory.add(info);
            
            String response = "{\"taskId\":\"" + taskId + "\",\"status\":\"scheduled\",\"delay\":" + delaySeconds + "}";
            sendResponse(exchange, 200, response);
        }
    }
    
    private class CancelTaskHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equals("POST")) {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            
            String requestBody = new String(exchange.getRequestBody().readAllBytes());
            Map<String, String> params = parseQueryParams(requestBody);
            String taskId = params.get("taskId");
            
            ScheduledFuture<?> future = scheduledTasks.remove(taskId);
            if (future != null && !future.isDone()) {
                future.cancel(true);
                taskHistory.stream()
                    .filter(t -> t.taskId.equals(taskId))
                    .findFirst()
                    .ifPresent(t -> {
                        t.status = "CANCELLED";
                        t.completedAt = new Date();
                    });
                sendResponse(exchange, 200, "{\"status\":\"cancelled\",\"taskId\":\"" + taskId + "\"}");
            } else {
                sendResponse(exchange, 404, "{\"error\":\"Task not found or already completed\"}");
            }
        }
    }
    
    private class GetTasksHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            StringBuilder json = new StringBuilder("{\"tasks\":[");
            synchronized (taskHistory) {
                for (int i = 0; i < taskHistory.size(); i++) {
                    TaskInfo task = taskHistory.get(i);
                    if (i > 0) json.append(",");
                    json.append(task.toJson());
                }
            }
            json.append("]}");
            sendResponse(exchange, 200, json.toString());
        }
    }
    
    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendResponse(exchange, 200, "{\"status\":\"healthy\"}");
        }
    }
    
    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, response.length());
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }
    
    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query != null && !query.isEmpty()) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=", 2);
                if (keyValue.length == 2) {
                    params.put(keyValue[0], decode(keyValue[1]));
                } else if (keyValue.length == 1) {
                    params.put(keyValue[0], "");
                }
            }
        }
        return params;
    }
    
    private String decode(String value) {
        if (value == null) return "";
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            return value;
        }
    }
    
    public static void main(String[] args) throws IOException {
        TaskScheduler scheduler = new TaskScheduler();
        scheduler.start();
    }
    
    private static class TaskInfo {
        String taskId;
        String taskName;
        String taskType;
        String status;
        Date scheduledAt;
        Date completedAt;
        
        TaskInfo(String taskId, String taskName, String taskType, String status, Date scheduledAt) {
            this.taskId = taskId;
            this.taskName = taskName;
            this.taskType = taskType;
            this.status = status;
            this.scheduledAt = scheduledAt;
        }
        
        String toJson() {
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"id\":\"").append(escapeJson(taskId)).append("\",");
            json.append("\"name\":\"").append(escapeJson(taskName)).append("\",");
            json.append("\"type\":\"").append(escapeJson(taskType)).append("\",");
            json.append("\"status\":\"").append(escapeJson(status)).append("\",");
            json.append("\"scheduledAt\":\"").append(scheduledAt != null ? scheduledAt.toString() : "").append("\",");
            json.append("\"completedAt\":\"").append(completedAt != null ? completedAt.toString() : "").append("\"");
            json.append("}");
            return json.toString();
        }
        
        private String escapeJson(String value) {
            if (value == null) return "";
            return value.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t");
        }
    }
}

