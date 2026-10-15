import { expect, test, type Page } from '@playwright/test'
import { setEnglishLocale } from './helpers/auth-fixtures'
import { E2eTestDataBuilder } from './helpers/test-data-builder'
import { loginWithCredentials } from './helpers/session'

async function findNamespaceCard(page: Page, slug: string) {
  await page.goto('/dashboard/namespaces')
  await expect(page.getByRole('heading', { name: 'My Namespaces' })).toBeVisible()
  const card = page.getByTestId(`namespace-card-${slug}`)
  const next = page.getByRole('button', { name: 'Next', exact: true })
  const firstCard = page.locator('[data-testid^="namespace-card-"]').first()
  await expect(firstCard).toBeVisible()
  while (!(await card.isVisible()) && await next.count() > 0 && await next.isEnabled()) {
    const previousId = await firstCard.getAttribute('data-testid')
    await next.click()
    await expect(firstCard).not.toHaveAttribute('data-testid', previousId!)
  }
  await expect(card.getByText(`@${slug}`, { exact: true })).toBeVisible()
  return card
}

test.describe('My Namespaces Data (Real API)', () => {
  test.beforeEach(async ({ page }, testInfo) => {
    await setEnglishLocale(page)
    await loginWithCredentials(page, {
      username: process.env.E2E_ADMIN_USERNAME ?? process.env.BOOTSTRAP_ADMIN_USERNAME ?? 'admin',
      password: process.env.E2E_ADMIN_PASSWORD ?? process.env.BOOTSTRAP_ADMIN_PASSWORD ?? 'ChangeMe!2026',
    }, testInfo)
  })

  test('shows namespace created by request helper', async ({ page }, testInfo) => {
    const builder = new E2eTestDataBuilder(page, testInfo)
    await builder.init()

    try {
      const namespace = await builder.createNamespace('e2e-list')
      await findNamespaceCard(page, namespace.slug)
    } finally {
      await builder.cleanup()
    }
  })

  test('deletes a writable namespace from the dashboard', async ({ page }, testInfo) => {
    const builder = new E2eTestDataBuilder(page, testInfo)
    await builder.init()

    try {
      const namespace = await builder.createNamespace('e2e-delete')

      const namespaceCard = await findNamespaceCard(page, namespace.slug)

      await namespaceCard.getByRole('button', { name: 'Enter space', exact: true }).click()
      await page.getByRole('navigation', { name: 'Space management' })
        .getByRole('link', { name: 'Settings', exact: true }).click()
      await expect(page.getByRole('heading', { name: 'Space lifecycle', exact: true })).toBeVisible()
      await page.getByTestId(`delete-namespace-${namespace.slug}`).click()
      await expect(page.getByTestId('namespace-action-dialog-delete')).toBeVisible()

      const deleteResponsePromise = page.waitForResponse((response) =>
        response.request().method() === 'DELETE'
          && response.url().includes(`/api/web/namespaces/${namespace.slug}`),
      )
      await page.getByTestId('namespace-action-confirm-delete').click()
      const deleteResponse = await deleteResponsePromise

      expect(deleteResponse.ok()).toBeTruthy()
      await expect(page).toHaveURL(/\/dashboard\/namespaces$/)
      await expect(page.getByTestId(`namespace-card-${namespace.slug}`)).toHaveCount(0)
    } finally {
      await builder.cleanup()
    }
  })

  test('hides the delete action when the namespace has dependent skills', async ({ page }, testInfo) => {
    const builder = new E2eTestDataBuilder(page, testInfo)
    await builder.init()

    try {
      const namespace = await builder.createNamespace('e2e-delete-guard')

      await builder.publishSkill(namespace.slug, {
        name: `Delete Guard ${Date.now()}`,
        description: 'Prevents namespace deletion during Playwright validation',
      })

      const namespaceCard = await findNamespaceCard(page, namespace.slug)
      await namespaceCard.getByRole('button', { name: 'Enter space', exact: true }).click()
      await page.getByRole('navigation', { name: 'Space management' })
        .getByRole('link', { name: 'Settings', exact: true }).click()
      await expect(page.getByRole('heading', { name: 'Space lifecycle', exact: true })).toBeVisible()
      await expect(page.getByTestId(`delete-namespace-${namespace.slug}`)).toHaveCount(0)
    } finally {
      await builder.cleanup()
    }
  })
})
