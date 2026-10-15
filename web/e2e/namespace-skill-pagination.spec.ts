import { expect, test } from '@playwright/test'
import { setEnglishLocale } from './helpers/auth-fixtures'
import { loginWithCredentials } from './helpers/session'
import { E2eTestDataBuilder } from './helpers/test-data-builder'

test.describe('Namespace Skill List Pagination (Real API)', () => {
  test.beforeEach(async ({ page }, testInfo) => {
    await setEnglishLocale(page)
    await loginWithCredentials(page, {
      username: process.env.E2E_ADMIN_USERNAME ?? process.env.BOOTSTRAP_ADMIN_USERNAME ?? 'admin',
      password: process.env.E2E_ADMIN_PASSWORD ?? process.env.BOOTSTRAP_ADMIN_PASSWORD ?? 'ChangeMe!2026',
    }, testInfo)
  })

  test('shows namespace page with skills and no pagination when under 20 skills', async ({ page }, testInfo) => {
    const builder = new E2eTestDataBuilder(page, testInfo)
    await builder.init()

    try {
      const namespace = await builder.createNamespace('e2e-pagination-small')
      await builder.publishSkill(namespace.slug)

      const searchResponsePromise = page.waitForResponse((response) => {
        const url = new URL(response.url())
        return url.pathname === '/api/web/skills'
          && url.searchParams.get('namespace') === namespace.slug
      })
      await page.goto(`/space/${namespace.slug}`)
      const searchResponse = await searchResponsePromise
      expect(searchResponse.ok()).toBeTruthy()
      const search: { data: { total: number } } = await searchResponse.json()
      expect(search.data.total).toBeLessThanOrEqual(20)

      await expect(page.getByText(`@${namespace.slug}`).first()).toBeVisible()
      await expect(page.getByRole('heading', { name: 'Skills', exact: true })).toBeVisible()

      // Fresh namespace keeps this fixture independent of previous tests.
      const skillList = page.getByRole('heading', { name: 'Skills', exact: true }).locator('..')
      await expect(skillList.getByRole('navigation', { name: 'Pagination', exact: true })).toHaveCount(0)
      await expect(skillList.getByRole('button', { name: 'Previous', exact: true })).toHaveCount(0)
      await expect(skillList.getByRole('button', { name: 'Next', exact: true })).toHaveCount(0)
    } finally {
      await builder.cleanup()
    }
  })

  test('shows pagination controls when there are more than 20 skills', async ({ page }, testInfo) => {
    const builder = new E2eTestDataBuilder(page, testInfo)
    await builder.init()

    try {
      const namespace = await builder.createNamespace('e2e-pagination-many')

      // Build a fake skill list to simulate >20 skills without hitting rate limits.
      const fakeSkill = (i: number) => ({
        id: 90000 + i,
        namespace: namespace.slug,
        slug: `fake-skill-${i}`,
        displayName: `Fake Skill ${i}`,
        summary: `Pagination test skill ${i}`,
        downloadCount: 0,
        starCount: 0,
        ratingAvg: 0,
        ratingCount: 0,
        versions: [{ id: 90000 + i, version: '1.0.0', status: 'PUBLISHED' }],
      })

      // Intercept the search API with a regex (glob '?' is ambiguous for literal '?').
      // Return fully mocked responses to avoid needing real published skills.
      await page.route(/\/api\/web\/skills\?/, async (route) => {
        const url = new URL(route.request().url())
        const reqPage = Number(url.searchParams.get('page') ?? '0')
        const items = reqPage === 0
          ? Array.from({ length: 20 }, (_, i) => fakeSkill(i))
          : [fakeSkill(20)]

        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 0,
            msg: 'success',
            data: { items, total: 21, page: reqPage, size: 20 },
          }),
        })
      })

      await page.goto(`/space/${namespace.slug}`)

      await expect(page.getByText(`@${namespace.slug}`).first()).toBeVisible()
      await expect(page.getByRole('heading', { name: 'Skills', exact: true })).toBeVisible()

      // Verify pagination controls appear
      const skillList = page.getByRole('heading', { name: 'Skills', exact: true }).locator('..')
      const pagination = skillList.getByRole('navigation', { name: 'Pagination', exact: true })
      const previousButton = pagination.getByRole('button', { name: 'Previous', exact: true })
      const nextButton = pagination.getByRole('button', { name: 'Next', exact: true })

      await expect(previousButton).toBeVisible()
      await expect(nextButton).toBeVisible()

      // First page: Previous should be disabled, Next should be enabled
      await expect(previousButton).toBeDisabled()
      await expect(nextButton).toBeEnabled()

      await expect(skillList.getByText('Fake Skill 0', { exact: true })).toBeVisible()
      // Navigate to second page
      await nextButton.click()

      // Second page: Previous should be enabled
      await expect(previousButton).toBeEnabled()
      await expect(nextButton).toBeDisabled()
      await expect(skillList.getByText('Fake Skill 20', { exact: true })).toBeVisible()
      await expect(skillList.getByText('Fake Skill 0', { exact: true })).toHaveCount(0)
      await expect(pagination.getByRole('button', { name: 'Go to page 2', exact: true })).toHaveAttribute('aria-current', 'page')

      // Navigate back to first page
      await previousButton.click()
      await expect(previousButton).toBeDisabled()
      await expect(nextButton).toBeEnabled()
      await expect(skillList.getByText('Fake Skill 0', { exact: true })).toBeVisible()
    } finally {
      await builder.cleanup()
    }
  })
})
