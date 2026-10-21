#!/usr/bin/env python3
"""main 릴리스 ECR 태그 준비. 부분 발행 재시도는 동일 커밋의 이미지에만 허용."""
import json
import os
import re
import subprocess
from pathlib import Path


def run(command):
    result = subprocess.run(command, capture_output=True, text=True, timeout=300)
    if result.returncode:
        raise RuntimeError("명령 실패: " + command[0] + ". 원문 비공개")
    return result.stdout.strip()


def resolve(repository, version, revision, registry, services, call=run):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?", version):
        raise ValueError("ECR 호환 릴리스 버전 형식 필요. build metadata(+) 사용 불가")
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("정확한 Git 커밋 SHA 필요")
    if registry != "721744297924.dkr.ecr.ap-northeast-2.amazonaws.com":
        raise ValueError("승인된 AWS 계정/리전의 ECR만 허용")
    if repository not in ("seonjalal/production/backend", "seonjalal/production/frontend"):
        raise ValueError("릴리스 Repository 경계 불일치")
    if services != (["main-backend", "payment-server"] if repository.endswith("/backend") else ["frontend"]):
        raise ValueError("릴리스 서비스 구성 불일치")
    repo = json.loads(call(["aws", "ecr", "describe-repositories", "--repository-names", repository, "--output", "json"]))
    if repo["repositories"][0]["imageTagMutability"] != "IMMUTABLE":
        raise ValueError("릴리스 ECR Repository는 IMMUTABLE이어야 함")
    outputs = {}
    for service in services:
        tag = version if service == "frontend" else service + "-" + version
        uri = registry + "/" + repository + ":" + tag
        # API 실패를 이미지 부재로 오인하지 않도록 batch-get의 failureCode를 구분.
        response = json.loads(call(["aws", "ecr", "batch-get-image", "--repository-name", repository,
                                    "--image-ids", "imageTag=" + tag, "--output", "json"]))
        images, failures = response.get("images", []), response.get("failures", [])
        if failures and (images or any(item.get("failureCode") != "ImageNotFound" for item in failures)):
            raise ValueError("ECR 이미지 존재 검사 실패")
        if not images and not failures:
            raise ValueError("ECR 이미지 검사 결과 누락")
        if images:
            call(["docker", "pull", "--platform", "linux/amd64", uri])
            actual = call(["docker", "image", "inspect", uri, "--format",
                           '{{index .Config.Labels "org.opencontainers.image.revision"}}'])
            if actual != revision:
                raise ValueError("기존 버전 태그가 다른 커밋을 가리킴. 덮어쓰기 금지")
        key = service.replace("-", "_")
        outputs[key + "_uri"] = uri
        outputs[key + "_exists"] = str(bool(images)).lower()
    return outputs


if __name__ == "__main__":
    try:
        services = ["main-backend", "payment-server"] if os.environ["RELEASE_COMPONENT"] == "backend" else ["frontend"]
        result = resolve(os.environ["RELEASE_ECR_REPOSITORY"], os.environ["RELEASE_VERSION"],
                         os.environ["GITHUB_SHA"], os.environ["ECR_REGISTRY"], services)
        with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
            for key, value in result.items():
                output.write(key + "=" + value + "\n")
        print("ECR_RELEASE_TARGETS_VERIFIED")
    except Exception as error:
        print("STOP: " + (str(error) if type(error) in (ValueError, RuntimeError) else type(error).__name__))
        raise SystemExit(1)
