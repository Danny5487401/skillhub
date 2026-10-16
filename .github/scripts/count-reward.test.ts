const script = new URL("./count-reward.ts", import.meta.url).pathname;
const decoder = new TextDecoder();

function assert(value: unknown, message: string): asserts value {
  if (!value) throw new Error(message);
}

async function fixture(
  run: (repo: string, tag: string, git: (...args: string[]) => Promise<string>) => Promise<void>,
) {
  const root = await Deno.makeTempDir({ prefix: "skillhub-reward-test-" });
  const repo = `${root}/repo`;
  await Deno.mkdir(repo);
  const git = async (...args: string[]) => {
    const result = await new Deno.Command("git", {
      args, cwd: repo, env: { GIT_TERMINAL_PROMPT: "0" },
    }).output();
    assert(result.success, decoder.decode(result.stderr));
    return decoder.decode(result.stdout).trim();
  };
  try {
    await git("init", "--bare", `${root}/origin.git`);
    await git("init");
    await git("config", "user.name", "Reward Test");
    await git("config", "user.email", "reward-test@example.invalid");
    await git("commit", "--allow-empty", "-m", "product");
    await git("tag", "-a", "v1.0.0", "-m", "product");
    await git("remote", "add", "origin", `${root}/origin.git`);
    await Deno.mkdir(`${repo}/bin`);
    await Deno.writeTextFile(`${repo}/bin/gh`, `#!/bin/sh
printf '%s\\0' "$@" >> gh-calls
if [ "$1" = api ]; then
  if [ "$RELEASE_STATUS" = 200 ]; then
    printf 'HTTP/2.0 200 OK\\n\\n{}\\n'
    exit 0
  fi
  printf 'HTTP/2.0 %s Error\\n\\n{}\\n' "$RELEASE_STATUS"
  exit 1
fi
if [ "$1" = release ] && [ "$2" = create ] && [ "$4" = --verify-tag ]; then
  git ls-remote --exit-code --tags origin "refs/tags/$3" >/dev/null
  exit $?
fi
exit 99
`);
    await Deno.chmod(`${repo}/bin/gh`, 0o700);
    await run(repo, `statistic-${new Date().toJSON().slice(0, 7)}`, git);
  } finally {
    await Deno.remove(root, { recursive: true });
  }
}

async function count(repo: string, releaseStatus: number) {
  return await new Deno.Command(Deno.execPath(), {
    args: ["run", "--no-lock", "--allow-run", "--allow-sys", "--allow-env", "--allow-read", script],
    cwd: repo,
    env: {
      PATH: `${repo}/bin:${Deno.env.get("PATH")}`,
      GH_TOKEN: "test-only", GH_HOST: "example.invalid", GH_REPO: "example/reward-test",
      RELEASE_STATUS: String(releaseStatus),
    },
  }).output();
}

Deno.test("completed statistic rerun leaves tags, product and identity unchanged", () => fixture(async (repo, tag, git) => {
  await git("tag", "-a", tag, "-m", "original summary");
  const before = await git("show-ref");
  const description = await git("describe", "--tags", "HEAD");
  const result = await count(repo, 200);
  assert(result.success, decoder.decode(result.stderr));
  assert(await git("show-ref") === before, "refs changed");
  assert(await git("describe", "--tags", "HEAD") === description, "product changed");
  assert(await git("config", "user.name") === "Reward Test", "identity changed");
  assert(!(await Deno.readTextFile(`${repo}/gh-calls`)).includes("create"), "duplicate release");
}));

Deno.test("missing release recovers notes from existing annotation without new rewards", () => fixture(async (repo, tag, git) => {
  const notes = "- payee: '@original'\n  reward: 17";
  await git("tag", "-a", tag, "-m", notes);
  await git("push", "origin", `refs/tags/${tag}`);
  const before = await git("show-ref");
  const result = await count(repo, 404);
  assert(result.success, decoder.decode(result.stderr));
  assert(await git("show-ref") === before, "existing tag changed");
  const calls = await Deno.readTextFile(`${repo}/gh-calls`);
  assert(calls.includes(`release\0create\0${tag}\0--verify-tag\0--latest=false\0--notes\0${notes}\0`), "wrong release notes or latest flag");
  assert(await git("config", "user.email") === "reward-test@example.invalid", "identity changed");
}));

Deno.test("API server failure is not treated as missing release", () => fixture(async (repo, tag, git) => {
  await git("tag", "-a", tag, "-m", "original summary");
  const result = await count(repo, 500);
  assert(!result.success, "server failure swallowed");
  assert(!(await Deno.readTextFile(`${repo}/gh-calls`)).includes("create"), "created on API failure");
}));

Deno.test("lightweight statistic cannot supply recovery notes", () => fixture(async (repo, tag, git) => {
  await git("tag", tag);
  const result = await count(repo, 404);
  assert(!result.success, "lightweight tag accepted");
  assert(!(await Deno.readTextFile(`${repo}/gh-calls`)).includes("create"), "created without annotation");
}));

Deno.test("empty annotation cannot supply recovery notes", () => fixture(async (repo, tag, git) => {
  await git("-c", "tag.gpgSign=false", "tag", "-a", tag, "--cleanup=verbatim", "-m", "");
  const result = await count(repo, 404);
  assert(!result.success, "empty notes accepted");
  assert(!(await Deno.readTextFile(`${repo}/gh-calls`)).includes("create"), "created without summary");
}));

Deno.test("no reward data creates no statistic or release", () => fixture(async (repo, _tag, git) => {
  const before = await git("show-ref");
  const result = await count(repo, 404);
  assert(result.success, decoder.decode(result.stderr));
  assert(await git("show-ref") === before, "unexpected tag");
  assert(!(await Deno.stat(`${repo}/gh-calls`).catch(() => null)), "unexpected gh call");
}));

Deno.test("unpublished local tag cannot cause gh to create a product tag", () => fixture(async (repo, tag, git) => {
  await git("tag", "-a", tag, "-m", "not pushed");
  const result = await count(repo, 404);
  assert(!result.success, "missing remote tag should fail verify-tag");
  assert(await git("ls-remote", "--tags", "origin") === "", "unexpected remote tag");
  assert((await Deno.readTextFile(`${repo}/gh-calls`)).includes("--verify-tag"), "missing remote guard");
}));
