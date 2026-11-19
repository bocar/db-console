import { mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { API_CLIENT_KEY } from '../api/client'
import { fakeApiClient } from '../api/test-support'
import type { QueryResult, ResultEditRequest, ResultEditResponse } from '../types'
import { useResultEditing } from './useResultEditing'
import { useResults } from './useResults'

/** 结果表格编辑状态机（F-17）：暂存、校验、请求构建、确认回路与守卫。 */
describe('useResultEditing', () => {
  let editing: ReturnType<typeof useResultEditing>
  let results: ReturnType<typeof useResults>
  let saveMock: Mock<(request: ResultEditRequest) => Promise<ResultEditResponse>>

  const editable = {
    tableParts: ['orders'],
    primaryKeys: ['id'],
    columns: [
      { name: 'id', type: 'INT', nullable: false, defaultValue: null, autoIncrement: true, primaryKey: true },
      { name: 'name', type: 'VARCHAR', nullable: false, defaultValue: null, autoIncrement: false, primaryKey: false },
      { name: 'note', type: 'VARCHAR', nullable: true, defaultValue: null, autoIncrement: false, primaryKey: false },
      { name: 'qty', type: 'INT', nullable: true, defaultValue: null, autoIncrement: false, primaryKey: false },
    ],
  }

  const query: QueryResult = {
    kind: 'QUERY',
    columns: [
      { name: 'id', type: 'INT' },
      { name: 'name', type: 'VARCHAR' },
      { name: 'note', type: 'VARCHAR' },
      { name: 'qty', type: 'INT' },
    ],
    rows: [
      [1, 'a', 'x', 3],
      [2, 'b', null, null],
    ],
    elapsedMs: 5,
    truncated: false,
    editable,
  }

  function setupHost(): ReturnType<typeof mount> {
    const host = defineComponent({
      setup() {
        editing = useResultEditing()
        results = useResults()
        return () => null
      },
    })
    return mount(host, {
      global: {
        provide: {
          [API_CLIENT_KEY]: fakeApiClient({
            sql: {
              execute: async () => {
                throw new Error('本用例不执行 sql.execute')
              },
              // 保存成功后的当前页重取：行值更新，editable 不携带（验证沿页保留）
              page: async () => ({
                ...query,
                rows: [
                  [1, 'a2', 'x', 3],
                  [2, 'b', 'note2', 4],
                ],
                editable: null,
              }),
              cancel: async () => ({ kind: 'COMPLETED' as const }),
            },
            resultEdit: {
              save: saveMock,
            },
          }),
        },
      },
    })
  }

  beforeEach(() => {
    saveMock = vi.fn<(request: ResultEditRequest) => Promise<ResultEditResponse>>()
    setupHost()
    results.showQuery(query, 'mysql-work-note', 'SELECT * FROM orders')
    editing.clearStaging()
  })

  it('简单单表结果可编辑；新增行使暂存变脏，移除后恢复干净', () => {
    expect(editing.isEditableResult.value).toBe(true)
    expect(editing.dirty.value).toBe(false)

    editing.addRow()
    expect(editing.dirty.value).toBe(true)
    expect(editing.newRows.value).toHaveLength(1)

    editing.removeNewRow(0)
    expect(editing.dirty.value).toBe(false)
  })

  it('构建请求：删除→更新→新增、清空即 SET NULL、空行静默丢弃', () => {
    editing.beginEdit(0, 2)
    editing.editDraft.value = ''
    editing.commitEdit()
    editing.deleteSelectedRows(new Set([1]))
    editing.addRow()
    editing.addRow()
    editing.setNewRowValue(0, 1, '新名字')

    const request = editing.buildRequest(null)
    expect(request.sourceId).toBe('mysql-work-note')
    expect(request.tableParts).toEqual(['orders'])
    expect(request.deletes).toEqual([{ key: { id: 2 } }])
    expect(request.updates).toEqual([{ key: { id: 1 }, set: { note: null } }])
    // 第二条空行静默丢弃（Q8）
    expect(request.inserts).toEqual([{ values: { name: '新名字' } }])
  })

  it('必填与数值校验：新增行缺必填列或数值格式错误时给出定位信息', () => {
    editing.addRow()
    editing.setNewRowValue(0, 3, 'abc')
    const message = editing.validateNewRows()
    expect(message).toContain('必填列 name')
    expect(message).toContain('qty 需要数值')
  })

  it('保存确认回路：首call返回预览，确认后携带凭据重交并刷新当前页', async () => {
    saveMock
      .mockResolvedValueOnce({
        kind: 'RESULT_EDIT_CONFIRMATION',
        statements: [
          { index: 0, section: 'UPDATE', changeIndex: 0, sql: 'UPDATE `orders` SET `name` = ? WHERE `id` = ?', params: ['x', 1], reason: 'PRODUCTION_WRITE', confirmationToken: 'token-0' },
        ],
      } satisfies ResultEditResponse)
      .mockResolvedValueOnce({
        kind: 'RESULT_EDIT_APPLIED',
        statements: [{ index: 0, section: 'UPDATE', changeIndex: 0, affectedRows: 1 }],
        elapsedMs: 4,
      } satisfies ResultEditResponse)

    editing.beginEdit(0, 1)
    editing.editDraft.value = 'x'
    editing.commitEdit()
    await editing.save()

    expect(editing.pendingBatch.value).not.toBeNull()
    await editing.confirmBatch()

    expect(saveMock).toHaveBeenCalledTimes(2)
    const secondRequest = saveMock.mock.calls[1][0] as ResultEditRequest
    expect(secondRequest.confirmationTokens).toEqual(['token-0'])
    // 保存成功：暂存清空 + 当前页重取 + 可编辑信息沿页保留
    expect(editing.dirty.value).toBe(false)
    expect(results.queryResult.value?.rows[0][1]).toBe('a2')
    expect(results.queryResult.value?.editable).toEqual(editable)
  })

  it('脏状态同步暂存门：暂存存在时结果生命周期拒绝翻页，清空后放行', () => {
    editing.addRow()
    expect(results.pendingChanges.value).toBe(true)
    editing.clearStaging()
    expect(results.pendingChanges.value).toBe(false)
  })
})
