import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ConfirmDialog from './ConfirmDialog.vue'

const baseProps = {
  title: '确认执行',
  sql: 'DELETE FROM t_dining_account WHERE id = 1',
  sourceName: 'MySQL 开发库',
  environment: '生产',
  implicitCommit: false,
}

function mountDialog(overrides: Partial<typeof baseProps> = {}) {
  return mount(ConfirmDialog, { props: { ...baseProps, ...overrides } })
}

describe('ConfirmDialog', () => {
  it('renders datasource, environment and sql as plain text', () => {
    const wrapper = mountDialog()

    expect(wrapper.text()).toContain('MySQL 开发库')
    expect(wrapper.text()).toContain('生产')
    expect(wrapper.text()).toContain('DELETE FROM t_dining_account WHERE id = 1')
  })

  it('shows manual-transaction hint for transactional writes', () => {
    expect(mountDialog().text()).toContain('加入当前手动事务')
  })

  it('shows implicit-commit restriction hint for DDL', () => {
    const wrapper = mountDialog({ implicitCommit: true })

    expect(wrapper.text()).toContain('独立连接执行')
    expect(wrapper.text()).toContain('不能随当前事务回滚')
  })

  it('emits confirm when dialog closes with confirm value', async () => {
    const wrapper = mountDialog()
    const dialog = wrapper.find('dialog')
    ;(dialog.element as HTMLDialogElement).returnValue = 'confirm'

    await dialog.trigger('close')

    expect(wrapper.emitted('confirm')).toHaveLength(1)
  })

  it('does not emit confirm when dialog closes with cancel value', async () => {
    const wrapper = mountDialog()
    const dialog = wrapper.find('dialog')
    ;(dialog.element as HTMLDialogElement).returnValue = 'cancel'

    await dialog.trigger('close')

    expect(wrapper.emitted('confirm')).toBeUndefined()
  })

  it('routes confirm and cancel through dialog form submit button values', () => {
    const wrapper = mountDialog()
    const submitValues = wrapper.findAll('button[type="submit"]').map((b) => b.attributes('value'))

    expect(submitValues).toContain('confirm')
    expect(submitValues).toContain('cancel')
  })
})
