/*
Quality Assurance Dashboard (Single-file Java application)
Features:
 - Manage Test Cases (CRUD)
 - Manage Defects (CRUD)
 - Schedule Test Runs using PriorityQueue (DSA)
 - Maintain execution history using LinkedList
 - Search test cases by name using a Trie (DSA)
 - Persist all data in MongoDB collections: testcases, defects, testruns, history

Requirements:
 - Java 11+
 - MongoDB running and reachable
 - mongodb-driver-sync jar (e.g., mongodb-driver-sync-4.11.0.jar) on classpath

Connection:
 - Update MONGO_URI constant below with your connection string

How to compile & run:
 javac -cp ".;mongodb-driver-sync-4.11.0.jar;bson-4.11.0.jar;mongodb-driver-core-4.11.0.jar" QADashboard.java
 java -cp ".;mongodb-driver-sync-4.11.0.jar;bson-4.11.0.jar;mongodb-driver-core-4.11.0.jar" QADashboard
 */

import com.mongodb.client.MongoClient;                            
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class QADashboard {

    // Set your MongoDB URI here
    private static final String MONGO_URI = "mongodb://localhost:27017"; // change if needed
    private static final String DB_NAME = "qa_dashboard";

    // DSA structures in-memory
    private final Trie testCaseTrie = new Trie();
    private final PriorityQueue<ScheduledRun> runQueue = new PriorityQueue<>(); // ordered by priority
    private final LinkedList<String> history = new LinkedList<>(); // execution history (most recent at end)

    private final MongoClient mongoClient;
    private final MongoDatabase database;

    private final MongoCollection<Document> testCaseColl;
    private final MongoCollection<Document> defectColl;
    private final MongoCollection<Document> testRunColl;
    private final MongoCollection<Document> historyColl;

    private final Scanner sc = new Scanner(System.in);
    private final AtomicInteger idCounter = new AtomicInteger(1000);

    public QADashboard() {
        mongoClient = MongoClients.create(MONGO_URI);
        database = mongoClient.getDatabase(DB_NAME);

        testCaseColl = database.getCollection("testcases");
        defectColl = database.getCollection("defects");
        testRunColl = database.getCollection("testruns");
        historyColl = database.getCollection("history");

        loadDataToMemory();
    }

    private void loadDataToMemory() {
        // load test case names into trie for search
        for (Document d : testCaseColl.find()) {
            String name = d.getString("name");
            if (name != null) {
                testCaseTrie.insert(name.toLowerCase());
            }
        }
        // load history from DB (optional)
        for (Document d : historyColl.find()) {
            String entry = d.getString("entry");
            if (entry != null) {
                history.add(entry);
            }
        }
        // idCounter: try to set based on max ids
        int maxId = 1000;
        for (Document d : testCaseColl.find()) {
            Integer id = d.getInteger("id");
            if (id != null && id > maxId) {
                maxId = id;
            }
        }
        idCounter.set(maxId + 1);
    }

    public void close() {
        mongoClient.close();
    }

    /* ------------------ Entities ------------------ */
    static class TestCase {

        int id;
        String name;
        String description;
        String priority; // e.g., P0, P1, P2
        boolean automated;

        Document toDoc() {
            return new Document("id", id)
                    .append("name", name)
                    .append("description", description)
                    .append("priority", priority)
                    .append("automated", automated);
        }

        static TestCase fromDoc(Document d) {
            TestCase t = new TestCase();
            t.id = d.getInteger("id");
            t.name = d.getString("name");
            t.description = d.getString("description");
            t.priority = d.getString("priority");
            t.automated = d.getBoolean("automated", false);
            return t;
        }
    }

    static class Defect {

        int id;
        int testCaseId;
        String title;
        String severity; // Critical, Major, Minor
        String status; // Open, In Progress, Closed

        Document toDoc() {
            return new Document("id", id)
                    .append("testCaseId", testCaseId)
                    .append("title", title)
                    .append("severity", severity)
                    .append("status", status);
        }

        static Defect fromDoc(Document d) {
            Defect def = new Defect();
            def.id = d.getInteger("id");
            def.testCaseId = d.getInteger("testCaseId");
            def.title = d.getString("title");
            def.severity = d.getString("severity");
            def.status = d.getString("status");
            return def;
        }
    }

    static class TestRunResult {

        int testCaseId;
        boolean passed;
        String notes;

        Document toDoc() {
            return new Document("testCaseId", testCaseId)
                    .append("passed", passed)
                    .append("notes", notes);
        }
    }

    static class ScheduledRun implements Comparable<ScheduledRun> {

        int runId;
        int testCaseId;
        int priority; // lower number => higher priority
        Instant scheduledAt;

        ScheduledRun(int runId, int testCaseId, int priority) {
            this.runId = runId;
            this.testCaseId = testCaseId;
            this.priority = priority;
            this.scheduledAt = Instant.now();
        }

        @Override
        public int compareTo(ScheduledRun o) {
            int byPriority = Integer.compare(this.priority, o.priority);
            if (byPriority != 0) {
                return byPriority;
            }
            return this.scheduledAt.compareTo(o.scheduledAt);
        }

        Document toDoc() {
            return new Document("runId", runId)
                    .append("testCaseId", testCaseId)
                    .append("priority", priority)
                    .append("scheduledAt", scheduledAt.toString());
        }
    }

    /* ------------------ Trie for name search ------------------ */
    static class TrieNode {

        Map<Character, TrieNode> children = new HashMap<>();
        boolean end = false;
    }

    static class Trie {

        private final TrieNode root = new TrieNode();

        void insert(String s) {
            TrieNode node = root;
            for (char c : s.toCharArray()) {
                node = node.children.computeIfAbsent(c, k -> new TrieNode());
            }
            node.end = true;
        }

        List<String> searchPrefix(String prefix) {
            List<String> results = new ArrayList<>();
            TrieNode node = root;
            for (char c : prefix.toCharArray()) {
                node = node.children.get(c);
                if (node == null) {
                    return results;
                }
            }
            collect(node, new StringBuilder(prefix), results);
            return results;
        }

        private void collect(TrieNode node, StringBuilder prefix, List<String> results) {
            if (node.end) {
                results.add(prefix.toString());
            }
            for (Map.Entry<Character, TrieNode> e : node.children.entrySet()) {
                prefix.append(e.getKey());
                collect(e.getValue(), prefix, results);
                prefix.deleteCharAt(prefix.length() - 1);
            }
        }
    }

    /* ------------------ Core CLI ------------------ */
    public void runCLI() {
        boolean running = true;
        while (running) {
            printMenu();
            String choice = sc.nextLine().trim();
            switch (choice) {
                case "1":
                    createTestCase();
                    break;
                case "2":
                    listTestCases();
                    break;
                case "3":
                    searchTestCases();
                    break;
                case "4":
                    deleteTestCase();
                    break;
                case "5":
                    scheduleTestRun();
                    break;
                case "6":
                    executeScheduledRuns();
                    break;
                case "7":
                    addDefect();
                    break;
                case "8":
                    listDefects();
                    break;
                case "9":
                    viewHistory();
                    break;
                case "0":
                    running = false;
                    break;
                default:
                    System.out.println("Invalid option");
            }
        }
        close();
        System.out.println("Exiting QADashboard. Goodbye.");
    }

    private void printMenu() {
        System.out.println("\n=== Quality Assurance Dashboard ===");
        System.out.println("1. Create Test Case");
        System.out.println("2. List Test Cases");
        System.out.println("3. Search Test Cases (prefix)");
        System.out.println("4. Delete Test Case");
        System.out.println("5. Schedule Test Run (priority-based)");
        System.out.println("6. Execute Scheduled Runs (simulate)");
        System.out.println("7. Add Defect");
        System.out.println("8. List Defects");
        System.out.println("9. View Execution History");
        System.out.println("0. Exit");
        System.out.print("Choose: ");
    }

    /* ------------------ Test Case CRUD ------------------ */
    private void createTestCase() {
        TestCase t = new TestCase();
        t.id = idCounter.getAndIncrement();
        System.out.print("Name: ");
        t.name = sc.nextLine().trim();
        System.out.print("Description: ");
        t.description = sc.nextLine().trim();
        System.out.print("Priority (P0/P1/P2): ");
        t.priority = sc.nextLine().trim();
        System.out.print("Automated? (y/n): ");
        t.automated = sc.nextLine().trim().equalsIgnoreCase("y");

        testCaseColl.insertOne(t.toDoc());
        testCaseTrie.insert(t.name.toLowerCase());
        System.out.println("Created TestCase id=" + t.id);
    }

    private void listTestCases() {
        System.out.println("--- Test Cases ---");
        for (Document d : testCaseColl.find()) {
            TestCase t = TestCase.fromDoc(d);
            System.out.printf("ID:%d | %s | %s | automated:%s\n", t.id, t.name, t.priority, t.automated);
        }
    }

    private void searchTestCases() {
        System.out.print("Prefix to search: ");
        String pre = sc.nextLine().trim().toLowerCase();
        List<String> names = testCaseTrie.searchPrefix(pre);
        if (names.isEmpty()) {
            System.out.println("No matches.");
            return;
        }
        System.out.println("Matches:");
        for (String name : names) {
            System.out.println(" - " + name);
        }
    }

    private void deleteTestCase() {
        System.out.print("TestCase ID to delete: ");
        int id = Integer.parseInt(sc.nextLine().trim());
        Document found = testCaseColl.find(Filters.eq("id", id)).first();
        if (found == null) {
            System.out.println("Not found");
            return;
        }
        testCaseColl.deleteOne(Filters.eq("id", id));
        // Note: trie cleanup omitted for simplicity (would require full rebuild)
        rebuildTrie();
        System.out.println("Deleted test case " + id);
    }

    private void rebuildTrie() {
        // rebuild trie from DB (simple and safe)
        testCaseTrie.root.children.clear();
        for (Document d : testCaseColl.find()) {
            String name = d.getString("name");
            if (name != null) {
                testCaseTrie.insert(name.toLowerCase());
            }
        }
    }

    /* ------------------ Scheduling & Execution (DSA: PriorityQueue) ------------------ */
    private void scheduleTestRun() {
        System.out.print("TestCase ID to schedule: ");
        int tcId = Integer.parseInt(sc.nextLine().trim());
        Document d = testCaseColl.find(Filters.eq("id", tcId)).first();
        if (d == null) {
            System.out.println("TestCase not found");
            return;
        }

        System.out.print("Numeric priority (1 highest, 10 lowest): ");
        int priority = Integer.parseInt(sc.nextLine().trim());
        int runId = new Random().nextInt(1_000_000);
        ScheduledRun sr = new ScheduledRun(runId, tcId, priority);
        runQueue.offer(sr);
        testRunColl.insertOne(sr.toDoc());
        System.out.println("Scheduled run " + runId + " for testCase " + tcId);
    }

    private void executeScheduledRuns() {
        System.out.println("Executing scheduled runs in priority order...");
        while (!runQueue.isEmpty()) {
            ScheduledRun sr = runQueue.poll();
            System.out.println("Running testCaseId=" + sr.testCaseId + " (runId=" + sr.runId + ")");

            // simulate execution: random pass/fail
            boolean passed = new Random().nextBoolean();
            TestRunResult res = new TestRunResult();
            res.testCaseId = sr.testCaseId;
            res.passed = passed;
            res.notes = passed ? "Passed" : "Failed - check logs";

            Document runDoc = new Document("runId", sr.runId)
                    .append("testCaseId", sr.testCaseId)
                    .append("priority", sr.priority)
                    .append("executedAt", Instant.now().toString())
                    .append("result", res.toDoc());
            testRunColl.insertOne(runDoc);

            String hist = String.format("Run %d: testCase %d -> %s", sr.runId, sr.testCaseId, res.passed ? "PASS" : "FAIL");
            history.add(hist);
            historyColl.insertOne(new Document("entry", hist).append("timestamp", Instant.now().toString()));

            System.out.println(hist);

            // If failed, auto-create a defect (simple heuristic)
            if (!passed) {
                Defect def = new Defect();
                def.id = idCounter.getAndIncrement();
                def.testCaseId = sr.testCaseId;
                def.title = "Auto-generated defect for test " + sr.testCaseId;
                def.severity = (sr.priority <= 3) ? "Critical" : "Major";
                def.status = "Open";
                defectColl.insertOne(def.toDoc());
                System.out.println("Auto-created defect id=" + def.id + " severity=" + def.severity);
            }
        }
        System.out.println("All scheduled runs executed.");
    }

    /* ------------------ Defects ------------------ */
    private void addDefect() {
        Defect def = new Defect();
        def.id = idCounter.getAndIncrement();
        System.out.print("TestCase ID: ");
        def.testCaseId = Integer.parseInt(sc.nextLine().trim());
        System.out.print("Title: ");
        def.title = sc.nextLine().trim();
        System.out.print("Severity (Critical/Major/Minor): ");
        def.severity = sc.nextLine().trim();
        def.status = "Open";
        defectColl.insertOne(def.toDoc());
        System.out.println("Created defect " + def.id);
    }

    private void listDefects() {
        System.out.println("--- Defects ---");
        for (Document d : defectColl.find()) {
            Defect def = Defect.fromDoc(d);
            System.out.printf("ID:%d | TestCase:%d | %s | %s\n", def.id, def.testCaseId, def.severity, def.status);
        }
    }

    /* ------------------ History ------------------ */
    private void viewHistory() {
        System.out.println("--- Execution History ---");
        int idx = 1;
        for (String h : history) {
            System.out.printf("%d. %s\n", idx++, h);
        }
    }

    /* ------------------ main ------------------ */
    public static void main(String[] args) {
        QADashboard app = new QADashboard();
        try {
            app.runCLI();
        } finally {
            app.close();
        }
    }
}
