import { describe, expect, it } from 'vitest'
import { resolveExecutionText } from './execution-text'

describe('resolveExecutionText', () => {
  it('executes the selection when one exists', () => {
    expect(resolveExecutionText('SELECT 1; SELECT 2', 'SELECT 1')).toBe('SELECT 1')
  })

  it('executes the full text when no selection exists', () => {
    expect(resolveExecutionText('SELECT 1', null)).toBe('SELECT 1')
  })

  it('falls back to full text for an empty selection', () => {
    expect(resolveExecutionText('SELECT 1', '')).toBe('SELECT 1')
  })
})
