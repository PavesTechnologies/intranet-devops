# Resource Allocation Problem: CI Pipeline Infrastructure
---

## Current CI Pipeline
The pipeline follows these sequential stages:
1.  **Checkout SCM**
2.  **Initialization**
3.  **Load Environment**
4.  **Checkout**
5.  **Print Branch Info**
6.  **Setup Environment**
7.  **Compile**
8.  **Parallel Checks**
9.  **Quality Gate**
10. **Unit Tests**
11. **SonarQube**

---

## Scenario 1: Single PR, Sequential Execution
*One developer pushes code. Jenkins runs mvn test, finishes, then runs SonarQube.*

### Infrastructure Recommendation
*   **Instance Type:** `t3.large` (2 vCPU, 8 GiB RAM)
*   **Family:** Burstable Performance (Intel/AMD)

### Cost & Billing (Mumbai Region)
*   **On-Demand Hourly:** $0.0832
*   **Monthly Total (24/7):** $60.74
*   **Spot Instance Price (Est.):** $18.22 (~70% savings)

| Pros | Cons |
| :--- | :--- |
| **Stability:** Very low risk of OOM (Out of Memory) since only one Java process runs at a time. | **Speed:** Slowest feedback loop. Total time = (Test Time + Sonar Time). |
| **Cost:** Most affordable option for small teams or low-frequency commits. | **Inefficiency:** CPU sits idle for 50% of the build duration during single-threaded tasks. |

---

## Scenario 2: Single PR, Parallel Execution
*One developer pushes code. Jenkins runs mvn test and SonarQube simultaneously.*

### Infrastructure Recommendation
*   **Instance Type:** `m7g.xlarge` (4 vCPU, 16 GiB RAM)
*   **Family:** General Purpose (AWS Graviton4)

### Cost & Billing (Mumbai Region)
*   **On-Demand Hourly:** $0.2710
*   **Monthly Total (24/7):** $197.83
*   **Spot Instance Price (Est.):** $59.35

| Pros | Cons |
| :--- | :--- |
| **Speed:** Reduces build time by roughly 40%. Developers get results significantly faster. | **Memory Stress:** Parallel Java processes can consume up to 6-8GB RAM instantly, requiring at least a 16GB machine. |
| **Efficiency:** Fully utilizes multiple CPU cores simultaneously. | **Cost:** Triple the cost of a basic T3 setup. |

---

## Scenario 3: Multiple PRs (3-4), Sequential Stages
*Three or four developers push code at once. Each build runs its stages one after another.*

### Infrastructure Recommendation
*   **Instance Type:** `m7g.2xlarge` (8 vCPU, 32 GiB RAM)
*   **Family:** General Purpose (AWS Graviton4)

### Cost & Billing (Mumbai Region)
*   **On-Demand Hourly:** $0.5420
*   **Monthly Total (24/7):** $395.66
*   **Spot Instance Price (Est.):** $118.70

| Pros | Cons |
| :--- | :--- |
| **Throughput:** High volume. Can handle the entire team's workload without a long build queue. | **Serialization:** While the system handles 4 PRs, each developer still waits for stages to finish one by one. |
| **Reliability:** 32GB RAM provides a safe buffer for 4 concurrent Spring Boot builds. | **Management:** Managing high concurrency on one instance requires strict Docker memory limits. |

---

## Scenario 4: Multiple PRs (3-4), Parallel Stages
*Three or four developers push code. Each build runs test and Sonar at the same time.*

### Infrastructure Recommendation
*   **Instance Type:** `m7g.4xlarge` (16 vCPU, 64 GiB RAM)
*   **Family:** General Purpose (AWS Graviton4)

### Cost & Billing (Mumbai Region)
*   **On-Demand Hourly:** $1.0840
*   **Monthly Total (24/7):** $791.32
*   **Spot Instance Price (Est.):** $237.40

| Pros | Cons |
| :--- | :--- |
| **Enterprise Speed:** Absolute fastest possible feedback for the entire organization. | **Extreme Cost:** Significant monthly expense for a single worker node. |
| **Isolation:** 16 vCPUs allow 8 concurrent heavy processes (4 tests, 4 scans) with minimal context switching. | **Complexity:** Requires high-performance EBS volumes (gp3 with high IOPS) to prevent disk bottlenecks. |

---
