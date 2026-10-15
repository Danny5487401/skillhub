import { expect, test } from '@playwright/test'
import { setEnglishLocale } from './helpers/auth-fixtures'
import { registerSession } from './helpers/session'
import { E2eTestDataBuilder } from './helpers/test-data-builder'

test.describe('Namespace Members Data (Real API)', () => {
  test.beforeEach(async ({ page }, testInfo) => {
    await setEnglishLocale(page)
    await registerSession(page, testInfo)
  })

  test('opens members management for writable namespace', async ({ page }, testInfo) => {
    const builder = new E2eTestDataBuilder(page, testInfo)
    await builder.init()

    try {
      const namespace = await builder.ensureWritableNamespace()

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
