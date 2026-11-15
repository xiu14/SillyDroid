'use strict';

// 批量重建 GIT_EXEC_PATH 里的 git 命令链接，由 termux-host-runtime.sh 的 install_git_core_links 调用。
// 每次 APK 升级 nativeLibraryDir 都会换路径，签名对不上，要把 150 多个链接全部重建一遍。
// shell 里每个链接要起 readlink / rm / ln 三个进程，Android 上一个进程约 15ms，合计 6 秒左右，
// 这里一个 Node 进程做完只要零点几秒。逻辑必须和 shell 版逐条对应；任何异常都以非零退出，
// 由 shell 退回原来的逐个创建。
//
// 用法：node sync-git-core-links.cjs <link_dir> <git_core_dir> <git_bin> <remote_http_bin> <signature>

const fs = require('node:fs');
const path = require('node:path');

const SIGNATURE_FILE_NAME = '.sillydroid-git-core-links.signature';

function ensureSymlink(target, linkPath) {
    try {
        if (fs.lstatSync(linkPath).isSymbolicLink() && fs.readlinkSync(linkPath) === target) {
            return;
        }
    } catch (error) {
        if (error.code !== 'ENOENT') throw error;
    }
    fs.rmSync(linkPath, { force: true });
    fs.symlinkSync(target, linkPath);
}

function removeEntry(entryPath) {
    // 对应 shell 的 rm -f：目录删不掉也不报错。
    try {
        fs.unlinkSync(entryPath);
    } catch {
        // ignore
    }
}

function main(argv) {
    const [linkDir, gitCoreDir, gitBin, remoteHttpBin, signature] = argv;
    if (!linkDir || !gitCoreDir || !gitBin || !remoteHttpBin || signature === undefined) {
        throw new Error('usage: sync-git-core-links.cjs <link_dir> <git_core_dir> <git_bin> <remote_http_bin> <signature>');
    }

    fs.mkdirSync(linkDir, { recursive: true });
    // 先作废签名：中途被杀时，下次启动一定会重新完整重建。
    removeEntry(path.join(linkDir, SIGNATURE_FILE_NAME));

    for (const name of fs.readdirSync(linkDir)) {
        if (name === 'git' || name.startsWith('git-') || name.startsWith('remote-')) {
            removeEntry(path.join(linkDir, name));
        }
    }

    ensureSymlink(gitBin, path.join(linkDir, 'git'));
    for (const name of [
        'git-remote-http', 'git-remote-https', 'git-remote-ftp', 'git-remote-ftps',
        'remote-http', 'remote-https', 'remote-ftp', 'remote-ftps',
    ]) {
        ensureSymlink(remoteHttpBin, path.join(linkDir, name));
    }

    let entries = [];
    try {
        entries = fs.readdirSync(gitCoreDir);
    } catch (error) {
        if (error.code !== 'ENOENT') throw error;
    }
    for (const name of entries.filter((entry) => entry.startsWith('git-')).sort()) {
        const entryPath = path.join(gitCoreDir, name);
        let linkTarget;
        try {
            if (!fs.lstatSync(entryPath).isSymbolicLink()) continue;
            linkTarget = fs.readlinkSync(entryPath);
        } catch {
            continue;
        }
        if (linkTarget === 'git' || linkTarget.endsWith('/git') || linkTarget === '../libexec/git-core/git') {
            ensureSymlink(gitBin, path.join(linkDir, name));
        } else if (linkTarget === 'git-remote-http' || linkTarget.endsWith('/git-remote-http')) {
            ensureSymlink(remoteHttpBin, path.join(linkDir, name));
        }
    }

    fs.writeFileSync(path.join(linkDir, SIGNATURE_FILE_NAME), signature);
}

try {
    main(process.argv.slice(2));
} catch (error) {
    console.error(`sync-git-core-links failed: ${error?.message ?? error}`);
    process.exit(1);
}
