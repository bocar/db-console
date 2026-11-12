import { describe, expect, it } from 'vitest'
import { MockApiClient } from './mock-client'
import { ApiClientError } from './http-client'

describe('MockApiClient', () => {
  it('accepts only the documented demo credentials', async () => {
    const client = new MockApiClient(0)

    await expect(client.auth.login('admin', 'db-console')).resolves.toEqual({ username: 'admin' })
    await expect(client.auth.login('admin', 'wrong')).rejects.toMatchObject({ code: 'BAD_CREDENTIALS' })
  })

  it('rejects unknown datasource ids', async () => {
    const client = new MockApiClient(0)

    await expect(client.dataSources.testConnection('no-such-source')).rejects.toMatchObject({ code: 'REQUEST_FAILED' })
    await expect(client.metadata.tables('no-such-source')).rejects.toMatchObject({ code: 'REQUEST_FAILED' })
  })

  it('runs plain queries without confirmation and records history', async () => {
    const client = new MockApiClient(0)

    const outcome = await client.sql.execute('mysql-work-note', 'SELECT 1 FROM orders')

    expect(outcome).toMatchObject({ kind: 'QUERY', truncated: false })
    const entries = await client.history.list('mysql-work-note')
    expect(entries).toHaveLength(1)
  })

  it('requires confirmation for production writes, then executes with the token', async () => {
    const client = new MockApiClient(0)

    const first = await client.sql.execute('dm8-navic', "UPDATE orders SET status = 'PAID' WHERE id = 1")
    expect(first).toMatchObject({ kind: 'CONFIRMATION_REQUIRED', reason: 'PRODUCTION_WRITE' })

    const token = (first as { confirmationToken: string }).confirmationToken
    const second = await client.sql.execute('dm8-navic', "UPDATE orders SET status = 'PAID' WHERE id = 1", token)
    expect(second).toMatchObject({ kind: 'UPDATE', uncommitted: true })
    expect(client.isTransactionPending('dm8-navic')).toBe(true)
  })

  it('blocks implicit-commit DDL while a transaction is pending', async () => {
    const client = new MockApiClient(0)
    const token = await confirmationTokenFor(client, "UPDATE orders SET remark = 'x' WHERE id = 1")
    await client.sql.execute('dm8-navic', "UPDATE orders SET remark = 'x' WHERE id = 1", token)

    await expect(client.sql.execute('dm8-navic', 'DROP TABLE orders')).rejects.toBeInstanceOf(ApiClientError)
  })

  it('finishes transactions and clears history per source', async () => {
    const client = new MockApiClient(0)
    const token = await confirmationTokenFor(client, "UPDATE orders SET remark = 'x' WHERE id = 1")
    await client.sql.execute('dm8-navic', "UPDATE orders SET remark = 'x' WHERE id = 1", token)

    await client.transaction.rollback('dm8-navic')
    expect(client.isTransactionPending('dm8-navic')).toBe(false)

    await client.history.clear('dm8-navic')
    await expect(client.history.list('dm8-navic')).resolves.toHaveLength(0)
  })

  it('cancels a running query when called before completion', async () => {
    const client = new MockApiClient(50)

    const pending = client.sql.execute('mysql-work-note', 'SELECT 1 FROM orders')
    await client.sql.cancel('mysql-work-note')

    await expect(pending).rejects.toThrow('查询已取消')
  })
})

async function confirmationTokenFor(client: MockApiClient, sql: string): Promise<string> {
  const outcome = await client.sql.execute('dm8-navic', sql)
  if (outcome.kind !== 'CONFIRMATION_REQUIRED') {
    throw new Error('预期需要确认的响应')
  }
  return outcome.confirmationToken
}
