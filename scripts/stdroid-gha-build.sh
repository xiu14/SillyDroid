#!/usr/bin/env bash
set -euo pipefail

# Stdroid GitHub Actions 构建 / 发布入口（自用 fork 专用，SillyDroid 不走 rbuild）
# 用法：
#   bash scripts/stdroid-gha-build.sh                          # 完整流程 debug APK（stage 1→4）
#   bash scripts/stdroid-gha-build.sh --tavern-tag latest      # 额外参数原样传给 build-stdroid-full.sh
#   bash scripts/stdroid-gha-build.sh --refresh                # 忽略缓存的 stage 1/3 产物
#   bash scripts/stdroid-gha-build.sh gradle :feature:settings:testDebugUnitTest ...   # 只跑 Gradle 任务
#   bash scripts/stdroid-gha-build.sh publish --changelog '修复 xxx'                  # 发布新版本到 GitHub Release
#   bash scripts/stdroid-gha-build.sh publish --changelog-file notes.md
# 流程：
#   1. 用临时 index 把工作区（含未提交改动和未被 gitignore 的新文件）快照成一个 commit，
#      父提交是 HEAD；不动当前分支、暂存区和工作区。
#   2. 强推到 fork 的 ci/stdroid 分支，触发 .github/workflows/stdroid-build.yml。
#   3. 等待构建完成；失败时打印失败步骤日志的末尾。
#   4. full：APK 下载到 artifacts/releases/android-apk/；gradle：测试报告下载到 artifacts/ci-reports/<run id>/；
#      publish：建 Release stdroid-v<versionName>（APK + version.json，设为 latest），App 据此检查更新。
# 注意：
#   - fork 是公开仓库，未提交的改动也会推上去，别把密钥放进未忽略的文件。
#   - 被 gitignore 的文件（如 assets 里的 rootfs / server-source.zip）不会上传；
#     CI 打 APK 只能走完整流程，由 stage 1/3 现做（有缓存时直接复用）。
#   - publish 要求工作区干净，并且先递增 android-tavern/gradle.properties 的 stdroidVersionCode / stdroidVersionName。

repo="${STDROID_GHA_REPO:-xiu14/SillyDroid}"
remote="${STDROID_GHA_REMOTE:-fork}"
branch='ci/stdroid'
workflow='stdroid-build.yml'

usage() {
    sed -n '4,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,3\}//'
}

mode='full'
changelog=''
case "${1:-}" in
    -h|--help)
        usage
        exit 0
        ;;
    gradle)
        mode='gradle'
        shift
        if [[ $# -eq 0 ]]; then
            echo 'gradle 模式需要至少一个 Gradle 任务' >&2
            usage >&2
            exit 1
        fi
        ;;
    publish)
        mode='publish'
        shift
        build_args=()
        while [[ $# -gt 0 ]]; do
            case "$1" in
                --changelog)
                    changelog="${2:-}"
                    shift 2
                    ;;
                --changelog-file)
                    changelog="$(cat "${2:?--changelog-file 需要文件路径}")"
                    shift 2
                    ;;
                *)
                    build_args+=("$1")
                    shift
                    ;;
            esac
        done
        set -- "${build_args[@]}"
        if [[ -z "${changelog//[[:space:]]/}" ]]; then
            echo 'publish 需要更新说明：--changelog "..." 或 --changelog-file <文件>' >&2
            exit 1
        fi
        ;;
esac
args="$*"
if [[ "$args" == *$'\n'* ]]; then
    echo '参数里不能有换行' >&2
    exit 1
fi

for tool in git gh curl python3; do
    command -v "$tool" >/dev/null || { echo "缺少命令：$tool" >&2; exit 1; }
done

root="$(git rev-parse --show-toplevel)"
cd "$root"

remote_url="$(git remote get-url "$remote" 2>/dev/null || true)"
if [[ "$remote_url" != *"$repo"* ]]; then
    echo "remote $remote 不指向 $repo（当前：${remote_url:-无}），可用 STDROID_GHA_REMOTE / STDROID_GHA_REPO 覆盖" >&2
    exit 1
fi

tmp_index="$(mktemp)"
trap 'rm -f "$tmp_index"' EXIT
GIT_INDEX_FILE="$tmp_index" git read-tree HEAD
GIT_INDEX_FILE="$tmp_index" git add -A
tree="$(GIT_INDEX_FILE="$tmp_index" git write-tree)"

head_short="$(git rev-parse --short HEAD)"
snapshot_note=''
if [[ "$tree" != "$(git rev-parse 'HEAD^{tree}')" ]]; then
    if [[ "$mode" == 'publish' ]]; then
        echo 'publish 要求工作区干净（Release 要对应一个真实提交），先提交改动：' >&2
        git status --short >&2
        exit 1
    fi
    snapshot_note=' + 未提交改动'
    echo '==> 工作区有未提交改动，一并推送：'
    git diff --stat HEAD | tail -n 1
fi

latest_version_url="https://github.com/$repo/releases/latest/download/version.json"
changelog_b64=''
if [[ "$mode" == 'publish' ]]; then
    read_property() {
        sed -n "s/^$1=//p" android-tavern/gradle.properties | head -n 1 | tr -d '\r[:space:]'
    }
    version_code="$(read_property stdroidVersionCode)"
    version_name="$(read_property stdroidVersionName)"
    status="$(curl -sL -o "$tmp_index.latest" -w '%{http_code}' "$latest_version_url" || true)"
    case "$status" in
        200) current_code="$(python3 -c 'import json,sys; print(int(json.load(open(sys.argv[1])).get("versionCode", 0)))' "$tmp_index.latest")" ;;
        404) current_code=0 ;;
        *) echo "读取线上 version.json 失败：HTTP $status" >&2; rm -f "$tmp_index.latest"; exit 1 ;;
    esac
    rm -f "$tmp_index.latest"
    echo "==> 发布 stdroid-v$version_name（versionCode $version_code，线上 $current_code）"
    if ! [[ "$version_code" =~ ^[0-9]+$ ]] || (( version_code <= current_code )); then
        echo "stdroidVersionCode=$version_code 必须大于线上 versionCode=$current_code，先改 android-tavern/gradle.properties" >&2
        exit 1
    fi
    if gh release view "stdroid-v$version_name" -R "$repo" >/dev/null 2>&1; then
        echo "Release stdroid-v$version_name 已存在，先改 stdroidVersionName" >&2
        exit 1
    fi
    changelog_b64="$(printf '%s' "$changelog" | base64 -w 0)"
fi

commit="$(printf 'ci: stdroid %s build of %s%s\n\nStdroid-CI-Mode: %s\nStdroid-CI-Args: %s\nStdroid-CI-Changelog-B64: %s\n' \
    "$mode" "$head_short" "$snapshot_note" "$mode" "$args" "$changelog_b64" | git commit-tree "$tree" -p HEAD)"

started_at="$(date +%s)"
echo "==> 推送 $commit → $repo $branch（mode=$mode${args:+, args=$args}）"
git push -q -f "$remote" "$commit:refs/heads/$branch"

run_id=''
for _ in $(seq 1 45); do
    run_id="$(gh run list -R "$repo" -w "$workflow" -b "$branch" -L 10 --json databaseId,headSha \
        -q ".[] | select(.headSha == \"$commit\") | .databaseId" | head -n 1)"
    [[ -n "$run_id" ]] && break
    sleep 2
done
if [[ -z "$run_id" ]]; then
    echo "90 秒内没找到 $commit 对应的 run，去 https://github.com/$repo/actions 看看" >&2
    exit 1
fi
echo "==> run: https://github.com/$repo/actions/runs/$run_id"

if ! gh run watch "$run_id" -R "$repo" --exit-status --interval 15 >/dev/null 2>&1; then
    conclusion="$(gh run view "$run_id" -R "$repo" --json conclusion -q .conclusion)"
    echo "==> 构建失败（$conclusion），失败步骤日志末尾：" >&2
    gh run view "$run_id" -R "$repo" --log-failed 2>/dev/null | sed -E 's/^[^\t]*\t[^\t]*\t//' | tail -n 80 >&2 || true
    if [[ "$mode" == 'gradle' ]]; then
        reports_dir="$root/artifacts/ci-reports/$run_id"
        if gh run download "$run_id" -R "$repo" -n stdroid-gradle-reports -D "$reports_dir" >/dev/null 2>&1; then
            echo "==> 测试报告：$reports_dir" >&2
        fi
    fi
    exit 1
fi
built_at="$(date +%s)"
echo "==> 构建完成，用时 $(( built_at - started_at ))s"

case "$mode" in
    full)
        apk_dir="$root/artifacts/releases/android-apk"
        download_dir="$apk_dir/.gha-download-$run_id"
        rm -rf "$download_dir"
        mkdir -p "$download_dir"
        echo '==> 下载 APK'
        gh run download "$run_id" -R "$repo" -n stdroid-apk-debug -D "$download_dir"
        for file in "$download_dir"/*; do
            mv -f "$file" "$apk_dir/"
        done
        rmdir "$download_dir"
        apk_path="$apk_dir/app-debug.apk"
        echo "==> 完成，总用时 $(( $(date +%s) - started_at ))s"
        printf 'apk=%s\nsha256=%s\nsize_bytes=%s\n' \
            "$apk_path" "$(sha256sum "$apk_path" | awk '{print $1}')" "$(stat -c '%s' "$apk_path")"
        ;;
    gradle)
        reports_dir="$root/artifacts/ci-reports/$run_id"
        if gh run download "$run_id" -R "$repo" -n stdroid-gradle-reports -D "$reports_dir" >/dev/null 2>&1; then
            echo "==> 报告：$reports_dir"
        fi
        echo "==> 完成，总用时 $(( $(date +%s) - started_at ))s"
        ;;
    publish)
        echo "==> 已发布：https://github.com/$repo/releases/tag/stdroid-v$version_name"
        # 刚发布的几秒内 releases/latest 的跳转可能还指向上一个版本，等它切过来再打印。
        for _ in $(seq 1 30); do
            latest_json="$(curl -sL "$latest_version_url?t=$(date +%s)" || true)"
            [[ "$latest_json" == *"\"versionCode\": $version_code,"* ]] && break
            sleep 2
        done
        echo '==> 线上 version.json（App 检查更新读的就是它）：'
        printf '%s' "$latest_json"
        echo
        echo "==> 完成，总用时 $(( $(date +%s) - started_at ))s"
        ;;
esac
