#!/usr/bin/env bash
set -euo pipefail

# Stdroid GitHub Actions 构建入口（自用 fork 专用，SillyDroid 不走 rbuild）
# 用法：
#   bash scripts/stdroid-gha-build.sh                          # 完整流程 debug APK（stage 1→4）
#   bash scripts/stdroid-gha-build.sh --tavern-tag latest      # 额外参数原样传给 build-stdroid-full.sh
#   bash scripts/stdroid-gha-build.sh --refresh                # 忽略缓存的 stage 1/3 产物
#   bash scripts/stdroid-gha-build.sh gradle :feature:settings:testDebugUnitTest ...   # 只跑 Gradle 任务
# 流程：
#   1. 用临时 index 把工作区（含未提交改动和未被 gitignore 的新文件）快照成一个 commit，
#      父提交是 HEAD；不动当前分支、暂存区和工作区。
#   2. 强推到 fork 的 ci/stdroid 分支，触发 .github/workflows/stdroid-build.yml。
#   3. 等待构建完成；失败时打印失败步骤日志的末尾。
#   4. full：APK 下载到 artifacts/releases/android-apk/；gradle：测试报告下载到 artifacts/ci-reports/<run id>/。
# 注意：
#   - fork 是公开仓库，未提交的改动也会推上去，别把密钥放进未忽略的文件。
#   - 被 gitignore 的文件（如 assets 里的 rootfs / server-source.zip）不会上传；
#     CI 打 APK 只能走完整流程，由 stage 1/3 现做（有缓存时直接复用）。

repo="${STDROID_GHA_REPO:-xiu14/SillyDroid}"
remote="${STDROID_GHA_REMOTE:-fork}"
branch='ci/stdroid'
workflow='stdroid-build.yml'

usage() {
    sed -n '4,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,3\}//'
}

mode='full'
if [[ "${1:-}" == '-h' || "${1:-}" == '--help' ]]; then
    usage
    exit 0
fi
if [[ "${1:-}" == 'gradle' ]]; then
    mode='gradle'
    shift
    if [[ $# -eq 0 ]]; then
        echo 'gradle 模式需要至少一个 Gradle 任务' >&2
        usage >&2
        exit 1
    fi
fi
args="$*"
if [[ "$args" == *$'\n'* ]]; then
    echo '参数里不能有换行' >&2
    exit 1
fi

for tool in git gh; do
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
    snapshot_note=' + 未提交改动'
    echo '==> 工作区有未提交改动，一并推送：'
    git diff --stat HEAD | tail -n 1
fi

commit="$(printf 'ci: stdroid %s build of %s%s\n\nStdroid-CI-Mode: %s\nStdroid-CI-Args: %s\n' \
    "$mode" "$head_short" "$snapshot_note" "$mode" "$args" | git commit-tree "$tree" -p HEAD)"

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

if [[ "$mode" == 'full' ]]; then
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
else
    reports_dir="$root/artifacts/ci-reports/$run_id"
    if gh run download "$run_id" -R "$repo" -n stdroid-gradle-reports -D "$reports_dir" >/dev/null 2>&1; then
        echo "==> 报告：$reports_dir"
    fi
    echo "==> 完成，总用时 $(( $(date +%s) - started_at ))s"
fi
