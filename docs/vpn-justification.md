# VPN & Access Control Options for Global Intranet

To maintain a secure "internal" environment while using public AWS infrastructure (S3 + CloudFront), we have identified three primary pathways. These options balance cost, security, and the "networking brake" mentioned in our architecture discussions.

## 1. Strict Intranet (AWS Client VPN)
This is the "Enterprise" managed approach where AWS provides the VPN infrastructure.

* **How it works:** We set up an AWS Client VPN endpoint. Every user installs the OpenVPN-based client. The AWS WAF is configured to block all traffic *except* requests originating from the VPN's Network Interface.
* **Pros:** * Highly secure and fully managed by AWS.
    * Integrates natively with Active Directory or SSO (SAML 2.0).
    * High availability and automatic scaling.
* **Cons:** * **Costly:** Approximately $0.10/hour per endpoint + $0.05/hour per user connection (approx. $300-$400/month for a small team).
* **Best For:** When security compliance and ease of management are the top priorities and budget allows for managed service overhead.

## 2. Hybrid Budget VPN (Custom WireGuard / OpenVPN)
We host our own VPN gateway on a small, dedicated EC2 instance.

* **How it works:** We deploy a lightweight VPN protocol (like WireGuard) on a `t3.micro` instance. We assign this instance an **Elastic IP**. We then configure the AWS WAF to whitelist only this specific Elastic IP.
* **Pros:** * **Cost-Effective:** Costs only the price of a small EC2 instance (~$12-$15/month).
    * Excellent performance (WireGuard is significantly faster than OpenVPN).
    * Total control over encryption and user keys.
* **Cons:** * **Manual Maintenance:** We are responsible for OS patching, VPN server updates, and user management.
* **Best For:** Our current stage, where we want high security and restricted access without high managed-service costs.

## 3. Geo-Locked & IP Whitelisting (The "No-VPN" Path)
We rely on the Web Application Firewall (WAF) without a formal encrypted tunnel for every user.

* **How it works:** If our primary office has a **Static Public IP**, we whitelist that IP in the WAF. For remote global users, we use **Geographic Blocking** to allow only specific countries and enforce strict application-level authentication (UMS/JWT).
* **Pros:** * **Lowest Cost:** Only the base price of AWS WAF (~$10-$25/month).
    * Best user experience (no additional software to toggle).
* **Cons:** * Less secure for remote users on dynamic home IPs (impossible to whitelist specific home IPs).
    * The site remains technically "reachable" on the public web, even if blocked by WAF logic.
* **Best For:** Testing phases or teams that work primarily from a central office with a fixed IP.

## Summary Comparison

| Feature | AWS Client VPN | Custom VPN (WireGuard) | WAF Geo-Lock |
| :--- | :--- | :--- | :--- |
| **Setup Effort** | Medium | High (Manual Setup) | Low |
| **Monthly Cost** | High ($300+) | Low (~$15) | Very Low (~$10) |
| **Security Level** | Excellent | Excellent | Moderate |
| **Maintenance** | Low | Medium | Zero |

## Recommendation
For our global intranet at this stage, we recommend starting with **Option 2 (Custom WireGuard VPN)**. It allows us to maintain a "Strict Intranet" feel where the CloudFront URL only works when the VPN is active. This keeps our costs low while ensuring the frontend remains inaccessible to the general public.
