from __future__ import annotations

import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]


class KubernetesProxyRoutingTests(unittest.TestCase):
    def test_k8s_uses_local_firecrawl_bridge_and_separate_exchange_proxy(self) -> None:
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
            {"finbot-egress-proxy"},
        )
        self.assertEqual(
            services,
            {"finbot-egress-proxy"},
        )

        exchange_script = deployments["finbot-egress-proxy"]["spec"]["template"]["spec"]["containers"][0]["args"][0]
        self.assertIn('*:*) ;;', exchange_script)
        self.assertIn("Bind %s", exchange_script)

    def test_k8s_routes_firecrawl_and_exchange_through_separate_ipv4_pools(self) -> None:
        kustomization = yaml.safe_load(
            (ROOT / "deploy" / "k8s" / "base" / "kustomization.yaml").read_text(encoding="utf-8")
        )
        finbot_env = next(
            generator
            for generator in kustomization["configMapGenerator"]
            if generator["name"] == "finbot-env"
        )
        values = dict(literal.split("=", 1) for literal in finbot_env["literals"])

        self.assertNotIn("FIRECRAWL_PROXY_POOL", values)
        self.assertEqual(values["FIRECRAWL_PROXY_IP_FAMILY"], "ipv4")
        self.assertEqual(values["FIRECRAWL_VLESS_MAX_NODES"], "8")
        self.assertEqual(values["EXCHANGE_PROXY_POOL"], "http://finbot-egress-proxy:8888")
        self.assertEqual(values["EXCHANGE_PROXY_IP_FAMILY"], "ipv4")
        self.assertEqual(values["SING_BOX_PATH"], "/usr/local/bin/sing-box")
        self.assertEqual(values["PROXY_RUNTIME_DIR"], "/tmp/finbot-proxy")


if __name__ == "__main__":
    unittest.main()
