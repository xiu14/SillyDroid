#!/usr/bin/env bash
set -euo pipefail

# Stdroid GitHub Actions 缓存保活（VPS cron 每天跑一次）
# 用法：
#   bash scripts/stdroid-gha-keepalive.sh            # 缓存 4 天内用过就跳过，否则触发一次 keepalive
#   bash scripts/stdroid-gha-keepalive.sh --force    # 不看时间，直接触发
# 背景：GitHub 会删掉 7 天没用过的 Actions 缓存；没缓存时完整构建要 11 分钟多，有缓存约 2.5 分钟。
# 做法：在 fork 的 ci/stdroid 当前提交上追加一个空提交（Stdroid-CI-Mode: keepalive），普通推送、不强推；
#   workflow 只把 full / gradle 两份缓存各拉一次，刷新「最后使用时间」，不编译、不存缓存，约 1 分钟。
#   有构建在跑或排队时跳过，留给下一天。fork 是公开仓库，Actions 不占分钟额度。
# cron（日志在 artifacts/，已被 gitignore）：
#   23 5 * * * /root/workspace/SillyDroid/scripts/stdroid-gha-keepalive.sh >> /root/workspace/SillyDroid/artifacts/gha-keepalive.log 2>&1

export PATH="/usr/local/bin:/usr/bin:/bin:${PATH:-}"

repo="${STDROID_GHA_REPO:-xiu14/SillyDroid}"
remote="${STDROID_GHA_REMOTE:-fork}"
branch='ci/stdroid'
workflow='stdroid-build.yml'
max_idle_days="${STDROID_KEEPALIVE_DAYS:-4}"

force='false'
case "${1:-}" in
    '') ;;
    --force) force='true' ;;
    -h|--help) sed -n '4,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,3\}//'; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
esac

log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }

cd "$(dirname "${BASH_SOURCE[0]}")/.."

remote_url="$(git remote get-url "$remote" 2>/dev/null || true)"
if [[ "$remote_url" != *"$repo"* ]]; then
    log "remote $remote 不指向 $repo（当前：${remote_url:-无}），跳过"
    exit 1
fi

# 每种缓存取最近一次使用时间；只要有一种超过阈值就触发。
caches_json="$(gh cache list -R "$repo" -L 100 --json key,lastAccessedAt)"
stale="$(python3 -c '
import datetime, json, sys
caches, max_days = json.loads(sys.argv[1]), float(sys.argv[2])
now = datetime.datetime.now(datetime.timezone.utc)
out = []
for prefix in ("stdroid-build-", "stdroid-gradle-"):
    times = [datetime.datetime.fromisoformat(c["lastAccessedAt"].replace("Z", "+00:00"))
             for c in caches if c["key"].startswith(prefix)]
    if not times:
        out.append(f"{prefix}*: 无缓存")
        continue
    idle = (now - max(times)).total_seconds() / 86400
    out.append(f"{prefix}*: {idle:.1f} 天没用" + ("（需要保活）" if idle > max_days else ""))
print("; ".join(out))
print("stale" if any("需要保活" in o for o in out) else "fresh")
' "$caches_json" "$max_idle_days")"
summary="$(head -n 1 <<< "$stale")"
verdict="$(tail -n 1 <<< "$stale")"

if [[ "$verdict" != 'stale' && "$force" != 'true' ]]; then
    log "无需保活：$summary"
    exit 0
fi
if [[ "$summary" != *天没用* ]]; then
    log "还没有任何缓存，等下次正常构建自己生成：$summary"
    exit 0
fi

busy="$(gh run list -R "$repo" -w "$workflow" -L 20 --json status \
    -q '[.[] | select(.status != "completed")] | length')"
if (( busy > 0 )); then
    log "有 $busy 个构建在跑或排队，今天跳过（它们本身就会刷新缓存）"
    exit 0
fi

base="$(git ls-remote "$remote" "refs/heads/$branch" | cut -f1)"
if [[ -z "$base" ]]; then
    log "$remote 上没有 $branch 分支，跳过"
    exit 1
fi
git fetch -q "$remote" "$base"
commit="$(printf 'ci: stdroid keepalive\n\nStdroid-CI-Mode: keepalive\n' | git commit-tree "$base^{tree}" -p "$base")"

log "保活：$summary"
# 不加 -f：如果这期间有人推了新构建，推送会被拒绝，不会覆盖。
git push -q "$remote" "$commit:refs/heads/$branch"

run_id=''
for _ in $(seq 1 45); do
    run_id="$(gh run list -R "$repo" -w "$workflow" -b "$branch" -L 10 --json databaseId,headSha \
        -q ".[] | select(.headSha == \"$commit\") | .databaseId" | head -n 1)"
    [[ -n "$run_id" ]] && break
    sleep 2
done
if [[ -z "$run_id" ]]; then
    log "90 秒内没找到 $commit 对应的 run"
    exit 1
fi

if gh run watch "$run_id" -R "$repo" --exit-status --interval 15 >/dev/null 2>&1; then
    matched="$(gh run view "$run_id" -R "$repo" --log 2>/dev/null \
        | grep -E 'Cache restored from key:' | sed -E 's/.*Cache restored from key: *//' | paste -sd ' ' || true)"
    log "完成：https://github.com/$repo/actions/runs/$run_id 命中：${matched:-（未识别）}"
else
    log "失败：https://github.com/$repo/actions/runs/$run_id"
    exit 1
fi
