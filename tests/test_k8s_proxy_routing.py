from __future__ import annotations

import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]


class KubernetesProxyRoutingTests(unittest.TestCase):
    def test_k8s_uses_separate_ip_family_bound_proxy_services(self) -> None:
        resources = list(
            yaml.safe_load_all(
                (ROOT / "deploy" / "k8s" / "base" / "egress-proxy.yaml").read_text(encoding="utf-8")
            )
        )
        deployments = {
            resource["metadata"]["name"]: resource
            for resource in resources
            if resource["kind"] == "Deployment"
        }
        services = {
            resource["metadata"]["name"]
            for resource in resources
            if resource["kind"] == "Service"
        }

        self.assertEqual(
            set(deployments),
            {"finbot-egress-proxy", "finbot-firecrawl-proxy"},
        )
        self.assertEqual(
            services,
            {"finbot-egress-proxy", "finbot-firecrawl-proxy"},
        )

        exchange_script = deployments["finbot-egress-proxy"]["spec"]["template"]["spec"]["containers"][0]["args"][0]
        firecrawl_script = deployments["finbot-firecrawl-proxy"]["spec"]["template"]["spec"]["containers"][0]["args"][0]
        self.assertIn('*:*) ;;', exchange_script)
        self.assertIn('*:*) bind_address="$address"', firecrawl_script)
        self.assertIn("Bind %s", exchange_script)
        self.assertIn("Bind %s", firecrawl_script)

    def test_k8s_routes_firecrawl_to_ipv6_and_exchange_to_ipv4(self) -> None:
        kustomization = yaml.safe_load(
            (ROOT / "deploy" / "k8s" / "base" / "kustomization.yaml").read_text(encoding="utf-8")
        )
        finbot_env = next(
            generator
            for generator in kustomization["configMapGenerator"]
            if generator["name"] == "finbot-env"
        )
        values = dict(literal.split("=", 1) for literal in finbot_env["literals"])

        self.assertEqual(values["FIRECRAWL_PROXY_POOL"], "http://finbot-firecrawl-proxy:8888")
        self.assertEqual(values["FIRECRAWL_PROXY_IP_FAMILY"], "ipv6")
        self.assertEqual(values["EXCHANGE_PROXY_POOL"], "http://finbot-egress-proxy:8888")
        self.assertEqual(values["EXCHANGE_PROXY_IP_FAMILY"], "ipv4")


if __name__ == "__main__":
    unittest.main()
