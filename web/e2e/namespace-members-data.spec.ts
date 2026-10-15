import { expect, test } from '@playwright/test'
import { setEnglishLocale } from './helpers/auth-fixtures'
import { loginWithCredentials } from './helpers/session'
import { E2eTestDataBuilder } from './helpers/test-data-builder'

test.describe('Namespace Members Data (Real API)', () => {
  test.beforeEach(async ({ page }, testInfo) => {
    await setEnglishLocale(page)
    await loginWithCredentials(page, {
      username: process.env.E2E_ADMIN_USERNAME ?? process.env.BOOTSTRAP_ADMIN_USERNAME ?? 'admin',
      password: process.env.E2E_ADMIN_PASSWORD ?? process.env.BOOTSTRAP_ADMIN_PASSWORD ?? 'ChangeMe!2026',
    }, testInfo)
  })

  test('opens members management for writable namespace', async ({ page }, testInfo) => {
    const builder = new E2eTestDataBuilder(page, testInfo)
    await builder.init()

    try {
      const namespace = await builder.createNamespace('e2e-members')
      expect(namespace.type).toBe('TEAM')

      await page.goto(`/dashboard/namespaces/${namespace.slug}/members`)

      await expect(page.getByRole('heading', { name: namespace.displayName, exact: true })).toBeVisible()
      await expect(page.getByRole('navigation', { name: 'Space management' })
        .getByRole('link', { name: 'Members', exact: true })).toHaveAttribute('aria-current', 'page')
      await expect(page.getByText(`@${namespace.slug}`, { exact: true })).toBeVisible()
      await expect(page.getByRole('table')).toBeVisible()
    } finally {
      await builder.cleanup()
    }
  })
})
