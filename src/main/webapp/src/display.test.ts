import { describe, expect, it } from 'vitest'
import {
  displayEnvironment,
  displayHistoryStatus,
  displayQueryValue,
  formatDuration,
} from './display'

describe('display helpers', () => {
  it('maps wire environments to display text', () => {
    expect(displayEnvironment('PRODUCTION')).toBe('生产')
    expect(displayEnvironment('TEST')).toBe('测试')
    expect(displayEnvironment('DEVELOPMENT')).toBe('开发')
  })

  it('formats durations from wire milliseconds', () => {
    expect(formatDuration(86)).toBe('86 ms')
  })

  it('distinguishes uncommitted transaction from execution status in history', () => {
    expect(displayHistoryStatus({ uncommitted: true, status: 'SUCCEEDED' })).toBe('未提交')
    expect(displayHistoryStatus({ uncommitted: false, status: 'SUCCEEDED' })).toBe('成功')
    expect(displayHistoryStatus({ uncommitted: false, status: 'FAILED' })).toBe('失败')
  })

  it('distinguishes SQL NULL from empty string when displaying cells', () => {
    expect(displayQueryValue(null)).toBe('NULL')
    expect(displayQueryValue('')).toBe('空字符串')
    expect(displayQueryValue(920000000000000000n.toString())).toBe('920000000000000000')
    expect(displayQueryValue(12.34)).toBe('12.34')
  })
})
