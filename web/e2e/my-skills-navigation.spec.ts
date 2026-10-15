import { expect, test } from '@playwright/test'
import { setEnglishLocale } from './helpers/auth-fixtures'
import { loginWithCredentials, registerSession } from './helpers/session'
import { E2eTestDataBuilder } from './helpers/test-data-builder'

test.describe('My Skills Navigation (Real API)', () => {
  test.beforeEach(async ({ page }, testInfo) => {
    await setEnglishLocale(page)
    await registerSession(page, testInfo)
  })

  test('shows a localized title while navigating with the original skill slug', async ({ page, browser }, testInfo) => {
    const builder = new E2eTestDataBuilder(page, testInfo)
    await builder.init()

    try {
      const namespace = await builder.ensureWritableNamespace()
      const displayName = '示例技能'
      const skill = await builder.publishSkill(namespace.slug, { displayName })

      await page.goto('/dashboard/skills')
      await expect(page.getByRole('heading', { name: 'My Skills' })).toBeVisible()

      await expect(page.getByRole('heading', { name: displayName, exact: true })).toBeVisible()
      await page.getByRole('heading', { name: displayName, exact: true }).click()
      await expect(page).toHaveURL(new RegExp(`/space/${skill.namespace}/${skill.slug}\\?returnTo=%2Fdashboard%2Fskills$`))

      await expect(page.getByRole('heading', { name: displayName, exact: true })).toBeVisible()

      await page.getByRole('button', { name: 'Back' }).click()
      await expect(page).toHaveURL('/dashboard/skills')

      const adminContext = await browser.newContext()
      try {
        const adminPage = await adminContext.newPage()
        await loginWithCredentials(adminPage, {
          username: process.env.E2E_ADMIN_USERNAME ?? process.env.BOOTSTRAP_ADMIN_USERNAME ?? 'admin',
          password: process.env.E2E_ADMIN_PASSWORD ?? process.env.BOOTSTRAP_ADMIN_PASSWORD ?? 'ChangeMe!2026',
        }, testInfo)
        const adminBuilder = new E2eTestDataBuilder(adminPage, testInfo)
        await adminBuilder.init()
        const reviewId = await adminBuilder.waitForPendingReview(skill.namespace, skill.slug, skill.version)
        await adminBuilder.approveReview(reviewId)
      } finally {
        await adminContext.close()
      }

      // The Chinese query appears only in display-name, not the slug or description.
      await builder.waitForSearchResult(displayName, skill.slug)
      await page.goto(`/search?q=${encodeURIComponent(displayName)}`)
      await expect(page.getByRole('heading', { name: displayName, exact: true })).toBeVisible()
    } finally {
      await builder.cleanup()
    }
  })
})
