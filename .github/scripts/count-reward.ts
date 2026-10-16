import { $, YAML } from "npm:zx";

import { createDetachedCommit } from "./git.ts";
import { Reward } from "./type.ts";

$.verbose = true;

const tagName = `statistic-${new Date().toJSON().slice(0, 7)}`;
const existingTag = await $`git show-ref --verify --quiet ${`refs/tags/${tagName}`}`.nothrow();
if (existingTag.exitCode === 0) {
  const release = await $`gh api ${`repos/{owner}/{repo}/releases/tags/${tagName}`} --include`.nothrow();
  if (release.exitCode === 0) process.exit(0);
  if (!/^HTTP\/[\d.]+ 404\b/m.test(release.stdout)) {
    throw new Error(`Could not check the existing statistic release: ${release.stderr}`);
  }

  // Recover from a previous push that succeeded before release creation failed.
  // The published tag is the source of truth, not a new calculation of rewards.
  const tagType = (await $`git cat-file -t ${`refs/tags/${tagName}`}`).stdout.trim();
  if (tagType !== "tag") throw new Error("The existing statistic tag is not annotated.");
  const notes = (await $`git tag -l --format="%(contents)" ${tagName}`).stdout.trimEnd();
  if (!notes.trim()) throw new Error("The existing statistic tag has no summary.");
  await $`gh release create ${tagName} --verify-tag --latest=false --notes ${notes}`;
  process.exit(0);
}
if (existingTag.exitCode !== 1) throw new Error("Could not check the statistic tag.");

const rawTags =
  await $`git tag --list "reward-*" --format="%(refname:short) %(creatordate:short)"`;

const lastMonth = new Date();
lastMonth.setMonth(lastMonth.getMonth() - 1);
const lastMonthStr = lastMonth.toJSON().slice(0, 7);

const rewardTags = rawTags.stdout
  .split("\n")
  .filter((line) => line.split(/\s+/)[1] >= lastMonthStr)
  .map((line) => line.split(/\s+/)[0]);

let rawYAML = "";

for (const tag of rewardTags)
  rawYAML += (await $`git tag -l --format="%(contents)" ${tag}`) + "\n";

if (!rawYAML.trim()) {
  console.warn("No reward data is found for the last month.");

  process.exit(0);
}

const rewards = YAML.parse(rawYAML) as Reward[];

const groupedRewards = Object.groupBy(rewards, ({ payee }) => payee);

const summaryList = Object.entries(groupedRewards).map(([payee, rewards]) => {
  const reward = rewards!.reduce(
    (acc, { currency, reward }) => {
      acc[currency] ??= 0;
      acc[currency] += reward;
      return acc;
    },
    {} as Record<string, number>,
  );

  return {
    payee,
    reward,
    accounts: rewards!.map(({ payee: _, ...account }) => account),
  };
});

const summaryText = YAML.stringify(summaryList);

console.log(summaryText);

await $`git config user.name "github-actions[bot]"`;
await $`git config user.email "github-actions[bot]@users.noreply.github.com"`;

const tagTarget = await createDetachedCommit(tagName);
await $`git tag -a ${tagName} ${tagTarget} -m ${summaryText}`;
await $`git push origin --tags --no-verify`;

await $`git config unset user.name`;
await $`git config unset user.email`;

// Keep "Latest" pointing at the newest product release
await $`gh release create ${tagName} --latest=false --notes ${summaryText}`;
