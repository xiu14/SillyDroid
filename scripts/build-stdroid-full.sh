#!/usr/bin/env bash
set -euo pipefail

# Stdroid Full Build Orchestrator（自用 fork 专用）
# Responsibilities:
# - 按 stage 1 → 4 顺序复用上游脚本完成完整构建，可在本机或 rbuild 构建机上执行：
#     rbuild exec bash scripts/build-stdroid-full.sh --build-type debug
# - 版本号固定取 android-tavern/gradle.properties 的 stdroidVersionCode / stdroidVersionName。
#   上游 build-tavern-android-local.sh 会按上游 GitHub release 计算 versionCode（如 1810001069）
#   并覆盖它，装到手机后会挡住后续 stdroid 版本的覆盖安装，也会让 filesite 更新检查失效。
# - Tavern tag 默认沿用当前 assets 里的版本，避免无意中升级 SillyTavern；--tavern-tag latest 才取上游最新。
# - stage 1 / stage 3 产物已存在时默认复用（stage 内部每次都会全量重做），--refresh 强制重建。
# Must not:
# - 修改上游 stage 脚本的语义或绕过 stage 边界。

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workspace_root="$(cd "$script_dir/.." && pwd)"
gradle_properties_path="$workspace_root/android-tavern/gradle.properties"
current_server_manifest_path="$workspace_root/android-tavern/app/src/main/assets/bootstrap/server/server-source-manifest.json"

runtime_rid='linux-arm64'
build_type='debug'
requested_tag='current'
refresh='0'

usage() {
    cat <<'EOF'
用法：bash ./scripts/build-stdroid-full.sh [选项]

选项：
  --build-type <type>    debug 或 release；默认 debug（release 需要外部注入正式签名参数）
  --tavern-tag <tag>     current（默认，沿用当前 assets 的版本）、latest 或具体 tag
  --refresh              忽略已有的 stage 1 / stage 3 产物，强制重建
  -h, --help             显示帮助

产物：artifacts/releases/android-apk/app-<build-type>.apk
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --build-type)
            build_type="${2:-}"
            shift 2
            ;;
        --tavern-tag)
            requested_tag="${2:-}"
            shift 2
            ;;
        --refresh)
            refresh='1'
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "未知参数：$1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

case "$build_type" in
    debug|release) ;;
    *)
        echo "--build-type 只支持 debug 或 release，收到：$build_type" >&2
        exit 1
        ;;
esac

read_gradle_property() {
    sed -n "s/^$1=//p" "$gradle_properties_path" | head -n 1 | tr -d '\r[:space:]'
}

version_code="$(read_gradle_property stdroidVersionCode)"
version_name="$(read_gradle_property stdroidVersionName)"
host_version="$(read_gradle_property staiAndroidHostVersion)"
if [[ -z "$version_code" || -z "$version_name" || -z "$host_version" ]]; then
    echo "gradle.properties 缺少 stdroidVersionCode / stdroidVersionName / staiAndroidHostVersion：$gradle_properties_path" >&2
    exit 1
fi

resolve_latest_tag() {
    bash "$workspace_root/scripts/resolve-tavern-build-plan.sh" --tavern-tag auto --build-type "$build_type" \
        | sed -n 's/^tavern_tag=//p' | tail -n 1 | tr -d '\r'
}

tavern_tag="$requested_tag"
if [[ "$tavern_tag" == 'current' ]]; then
    tavern_tag="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("tag") or "")' \
        "$current_server_manifest_path" 2>/dev/null || true)"
    if [[ -z "$tavern_tag" ]]; then
        echo "当前 assets 里没有 server-source-manifest.json 的 tag，改用上游最新版本。" >&2
        tavern_tag='latest'
    fi
fi
if [[ "$tavern_tag" == 'latest' || "$tavern_tag" == 'auto' ]]; then
    tavern_tag="$(resolve_latest_tag)"
fi
if [[ -z "$tavern_tag" ]]; then
    echo '无法确定 SillyTavern tag。' >&2
    exit 1
fi

rootfs_path="$workspace_root/artifacts/releases/rootfs/$runtime_rid/tavern-rootfs-$runtime_rid.zip"
dependency_dir="$workspace_root/artifacts/releases/dependency-packs/$runtime_rid"
server_dir="$workspace_root/artifacts/releases/server-source/$runtime_rid/$tavern_tag"
server_source_path="$server_dir/server-source.zip"
apk_path="$workspace_root/artifacts/releases/android-apk/app-$build_type.apk"

printf '==> stdroid build plan\n'
printf 'tavern_tag=%s\nbuild_type=%s\nversion_code=%s\nversion_name=%s\nhost_version=%s\n' \
    "$tavern_tag" "$build_type" "$version_code" "$version_name" "$host_version"

printf '\n==> stage 1/4 rootfs\n'
if [[ "$refresh" == '0' && -f "$rootfs_path" ]]; then
    printf '复用已有 runtime image：%s\n' "$rootfs_path"
else
    bash "$workspace_root/scripts/build-tavern-android-runtime-image.sh" \
        --runtime-rid "$runtime_rid" \
        --output "$rootfs_path" \
        --artifact-name "tavern-rootfs-$runtime_rid"
fi

printf '\n==> stage 2/4 dependency packs\n'
bash "$workspace_root/scripts/build-tavern-dependency-packs.sh" \
    --runtime-rid "$runtime_rid" \
    --target-root "$dependency_dir"

printf '\n==> stage 3/4 server source\n'
if [[ "$refresh" == '0' && -f "$server_source_path" && -f "$server_dir/server-source-manifest.json" ]]; then
    printf '复用已有 server source：%s\n' "$server_source_path"
else
    bash "$workspace_root/scripts/sync-tavern-android-bootstrap.sh" \
        --runtime-rid "$runtime_rid" \
        --tag "$tavern_tag" \
        --target-root "$server_dir"
fi

printf '\n==> stage 4/4 apk\n'
# 四个变量都非空时 stage 4 不再解析上游构建计划，直接使用 stdroid 版本号。
export SILLYDROID_ANDROID_HOST_VERSION="$host_version"
export SILLYDROID_ANDROID_UPSTREAM_VERSION="$tavern_tag"
export SILLYDROID_ANDROID_VERSION_NAME="$version_name"
export SILLYDROID_ANDROID_VERSION_CODE="$version_code"

bash "$workspace_root/scripts/build-tavern-android-apk.sh" \
    --runtime-rid "$runtime_rid" \
    --build-type "$build_type" \
    --tag "$tavern_tag" \
    --runtime-image "$rootfs_path" \
    --server-source "$server_source_path"

printf '\n==> done\n'
printf 'apk=%s\n' "$apk_path"
printf 'sha256=%s\n' "$(sha256sum "$apk_path" | awk '{print $1}')"
printf 'size_bytes=%s\n' "$(stat -c '%s' "$apk_path")"
