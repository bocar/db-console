import type { ApiClient } from './client'

/**
 * 测试共享的 ApiClient 假实现：全命名空间的默认空操作，
 * 按用例覆盖所需分区，避免各测试文件手写整份客户端字面量。
 */
export function fakeApiClient(overrides: Partial<ApiClient> = {}): ApiClient {
  const base: ApiClient = {
    auth: {
      login: async () => ({ username: 'admin' }),
      logout: async () => undefined,
      session: async () => ({ username: 'admin' }),
    },
    dataSources: {
      list: async () => [],
      testConnection: async () => ({ ok: true, kind: 'NONE', message: '连接正常' }),
    },
    metadata: { tables: async () => [], tableStructure: async () => { throw new Error('未覆盖') }, commentStatement: async () => ({ sql: '' }) },
    sql: {
      execute: async () => {
        throw new Error('fakeApiClient: sql.execute 未在用例中覆盖')
      },
      page: async () => {
        throw new Error('fakeApiClient: sql.page 未在用例中覆盖')
      },
      cancel: async () => ({ kind: 'COMPLETED' as const }),
    },
    transaction: {
      begin: async () => ({ active: true, sourceId: null }),
      commit: async () => ({ active: false, sourceId: null }),
      rollback: async () => ({ active: false, sourceId: null }),
      status: async () => ({ active: false, sourceId: null }),
    },
    history: { list: async () => [], clear: async () => undefined },
  }
  return { ...base, ...overrides } as ApiClient
}
