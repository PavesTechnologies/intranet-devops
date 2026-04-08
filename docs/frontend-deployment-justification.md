# Architectural Decision Record: Frontend Deployment Strategy

## 1. Executive Summary
The organization’s intranet platform consists of a centralized React-based frontend and six backend services. This document outlines the evaluation of deployment strategies for the frontend application to ensure high performance for a global user base, ease of deployment, and robust testing capabilities.

## 2. Industry Standard Deployment Options
The following methods represent the current industry standards for deploying Single Page Applications (SPAs):

* **Managed Static Hosting (e.g., Vercel, Netlify):** Offers excellent developer experience and automated previews but is often restricted in corporate environments due to data residency and security compliance requirements.
* **Static Site Hosting (AWS S3 + CloudFront):** Involves serving static assets from an object store through a Content Delivery Network (CDN). This is the standard for high-performance, cost-effective global delivery.
* **Containerized Hosting (Docker + Nginx):** Packages the application and web server into a single portable image, ensuring environment consistency (Dev-Prod parity).
* **Virtual Machine Hosting (Nginx on EC2):** The traditional approach of serving files from a dedicated server instance. While simple, it lacks modern scaling and automated rollback features.

## 3. Evaluated Feasible Options
Based on the specific requirements of the intranet ecosystem—including a global user base and the need for integration testing—two primary paths were identified as feasible:

### Option A: AWS S3 + CloudFront (Static Hosting)
* **Pros:**
    * **Global Performance:** Files are cached at edge locations, reducing latency for international users.
    * **Low-Cost Previews:** Enables "Preview URLs" for different branch deployments using S3 prefixes at near-zero additional cost.
    * **Serverless Efficiency:** Eliminates the need for OS patching or Nginx maintenance.
* **Cons:**
    * **Networking Complexity:** Requires specific configurations (WAF, OAC) to maintain private intranet security.
    * **Build-time Configuration:** Environment variables are typically baked into the application during the build process.

### Option B: Docker on EC2 (Containerized Hosting)
* **Pros:**
    * **Consistency:** Ensures the exact same environment runs in development and production.
    * **Integration Testing:** Allows the entire 7-service stack to be spun up locally or in CI/CD via Docker Compose.
    * **Native Privacy:** Operates naturally within a private Virtual Private Cloud (VPC).
* **Cons:**
    * **Regional Latency:** Performance is limited by the physical location of the EC2 instance.
    * **Resource Overhead:** Managing containers for multiple branch previews can become resource-intensive and costly.

## 4. Final Decision and Justification
The selected strategy for the frontend deployment is **AWS S3 + CloudFront**.

### Reasoning
1.  **Global Performance:** Given the global distribution of the user base, a centralized server would result in poor load times. CloudFront ensures the UI is delivered from the nearest edge location.
2.  **Development Velocity:** The ability to generate "Preview URLs" for every Pull Request is a critical requirement. S3 prefixes allow for hosting multiple versions of the application simultaneously without the overhead of managing multiple running containers.
3.  **Scalability:** The serverless nature of S3 and CloudFront allows the infrastructure to scale automatically with user growth while maintaining a lower cost profile compared to persistent EC2 instances.

## 5. Security and Implementation Strategy
To address the "intranet" security requirements and the "networking brake" associated with public CDNs, the following mitigations will be implemented:

* **Access Control:** Use **AWS WAF** to restrict access to corporate VPN IP ranges and specific geographic regions.
* **Data Integrity:** Implement **CloudFront Origin Access Control (OAC)** to ensure the S3 bucket is not accessible via the public internet.
* **Secure Backend Connectivity:** Backend services will remain in private subnets, with CloudFront communicating via **VPC Origins** or a unified **API Gateway** to maintain a secure, end-to-end private network feel.
