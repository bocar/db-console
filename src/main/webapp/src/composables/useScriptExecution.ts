import { computed, ref } from 'vue'
import { useApiClient } from '../api/client'
import { apiErrorMessage } from '../api/error-message'
import type {
  QueryResult,
  ScriptAnalyzeResult,
  ScriptStatementPlan,
  ScriptStatementState,
  UpdateResult,
} from '../types'
import { isStopStatus } from '../display'
import { useToast } from './useToast'

const filename = ref('')
const analyzedSourceId = ref('')
const analyzedScriptId = ref('')
const plans = ref<ScriptStatementPlan[]>([])
const states = ref<ScriptStatementState[]>([])
const analyzing = ref(false)
const running = ref(false)
const cancelRequested = ref(false)
const lastRunDurationMs = ref<number | null>(null)

/**
 * 脚本执行编排（F-13）：整份脚本由后端预校验并逐语句签发确认凭据（F-09 绑定不变式），
 * 前端按序逐条调用脚本执行端点——每条语句独立提交、独立受限时与并发约束；
 * 失败、取消或结果未知即停止，可从指定条次恢复；完整跑完才记一条汇总历史。
 * 断连或刷新时循环自然停止，后续语句不会被执行（与「结果未知永不重试」一致）。
 */
export function useScriptExecution() {
  const api = useApiClient()
  const toast = useToast()

  type ScriptStatementOutcome = Awaited<ReturnType<typeof api.script.executeStatement>>

  const hasScript = computed(() => plans.value.length > 0)
  const needsConfirmationCount = computed(
    () => plans.value.filter((plan) => plan.confirmationToken !== null).length,
  )
  const progress = computed(() => {
    const succeeded = states.value.filter((state) => state.status === 'SUCCEEDED').length
    const stoppedAt = states.value.findIndex((state) => isStopStatus(state.status))
    return { succeeded, total: states.value.length, stoppedAt }
  })
  const allSucceeded = computed(
    () => states.value.length > 0 && states.value.every((state) => state.status === 'SUCCEEDED'),
  )

  /** 上传并预校验：客户端先做扩展名初检，服务端为最终裁决（F-13 约束收口在后端）。 */
  async function loadFile(file: File, sourceId: string): Promise<void> {
    if (running.value) return
    if (!/\.(sql|txt)$/i.test(file.name)) {
      toast.notify('仅支持 .sql 或 .txt 脚本文件。')
      return
    }
    analyzing.value = true
    try {
      const content = await file.text()
      const result = await api.script.analyze(sourceId, file.name, content)
      applyResult(result)
      toast.notify(
        result.valid
          ? `预校验通过，共 ${result.statements.length} 条语句。`
          : `预校验未通过（${result.errors.length} 条问题），整份脚本未执行。`,
      )
    } catch (error) {
      toast.notify(apiErrorMessage(error, '脚本上传失败。'))
    } finally {
      analyzing.value = false
    }
  }

  function applyResult(result: ScriptAnalyzeResult): void {
    filename.value = result.filename
    analyzedSourceId.value = result.sourceId
    // 运行标识仅在整份通过时签发；汇总历史据其由服务端取回全文，前端不再持有脚本文本
    analyzedScriptId.value = result.scriptId ?? ''
    plans.value = result.statements
    states.value = result.statements.map((plan) => ({
      plan,
      status: 'PENDING',
      elapsedMs: null,
      affectedRows: null,
      queryResult: null,
      message: '',
    }))
    lastRunDurationMs.value = null
    cancelRequested.value = false
  }

  /**
   * 从指定下标（0 起）开始顺序执行。确认凭据取自预校验签发；
   * 中途收到 CONFIRMATION_REQUIRED 说明凭据已失效（如服务重启），整体停止并要求重新上传。
   * currentSourceId 为发起执行时的当前数据源：与预校验数据源不一致即拒绝（凭据绑定数据源，
   * 防止切换数据源后把脚本误执行到另一个库）。
   */
  async function run(startIndex: number, currentSourceId: string): Promise<void> {
    if (running.value || plans.value.length === 0) return
    if (startIndex >= plans.value.length) return
    if (currentSourceId !== analyzedSourceId.value) {
      toast.notify('数据源已切换，预校验结果与确认凭据已失效，请重新上传脚本。')
      return
    }
    running.value = true
    cancelRequested.value = false
    const startedAt = Date.now()
    let stopped = false
    for (let position = startIndex; position < states.value.length; position++) {
      if (cancelRequested.value) {
        markSkippedFrom(position)
        break
      }
      const plan = plans.value[position]
      states.value[position] = { ...states.value[position], status: 'RUNNING' }
      try {
        const outcome = await api.script.executeStatement(
          analyzedSourceId.value,
          plan.sql,
          plan.confirmationToken ?? undefined,
        )
        states.value[position] = applyOutcome(states.value[position], outcome)
        if (isStopStatus(states.value[position].status)) {
          markSkippedFrom(position + 1)
          stopped = true
          break
        }
      } catch (error) {
        states.value[position] = { ...states.value[position], status: 'FAILED', message: apiErrorMessage(error, '执行失败。') }
        markSkippedFrom(position + 1)
        stopped = true
        break
      }
    }
    running.value = false
    lastRunDurationMs.value = Date.now() - startedAt
    if (allSucceeded.value) {
      await recordSummaryHistory()
    } else if (stopped) {
      // F-13：停止时报告所在条次与剩余未执行数量
      const failedIndex = progress.value.stoppedAt + 1
      const skipped = states.value.filter((state) => state.status === 'SKIPPED').length
      toast.notify(`脚本已停止：第 ${failedIndex} 条未成功，剩余 ${skipped} 条未执行，可从对应语句恢复。`)
    }
  }

  function applyOutcome(previous: ScriptStatementState, outcome: ScriptStatementOutcome): ScriptStatementState {
    if (outcome.kind === 'CONFIRMATION_REQUIRED') {
      return {
        ...previous,
        status: 'FAILED',
        message: '确认凭据已失效（可能因服务重启或脚本变更），请重新上传脚本后再执行。',
      }
    }
    if (outcome.kind === 'UNKNOWN_OUTCOME') {
      return { ...previous, status: 'UNKNOWN_OUTCOME', message: outcome.message }
    }
    if (outcome.kind === 'CANCELLED') {
      return { ...previous, status: 'CANCELLED', elapsedMs: outcome.elapsedMs }
    }
    if (outcome.kind === 'QUERY') {
      return {
        ...previous,
        status: 'SUCCEEDED',
        elapsedMs: outcome.elapsedMs,
        queryResult: outcome as QueryResult,
      }
    }
    const update = outcome as UpdateResult
    return { ...previous, status: 'SUCCEEDED', elapsedMs: update.elapsedMs, affectedRows: update.affectedRows }
  }

  function markSkippedFrom(start: number): void {
    for (let position = start; position < states.value.length; position++) {
      if (states.value[position].status === 'PENDING' || states.value[position].status === 'RUNNING') {
        states.value[position] = { ...states.value[position], status: 'SKIPPED' }
      }
    }
  }

  /** 取消：请求当前语句取消（终态由其响应给出），并阻止后续语句下发。 */
  function cancelRun(): void {
    if (!running.value || cancelRequested.value) return
    cancelRequested.value = true
    void api.sql.cancel(analyzedSourceId.value).catch(() => {
      // 取消请求失败不改变循环：当前语句的执行响应仍会按原终态返回
    })
  }

  /** 汇总历史（F-13）：仅完整执行结束的运行记录；失败不影响已完成语句的结果展示。 */
  async function recordSummaryHistory(): Promise<void> {
    if (!analyzedScriptId.value) return
    try {
      await api.script.recordRun(
        analyzedSourceId.value,
        analyzedScriptId.value,
        lastRunDurationMs.value ?? 0,
      )
      // 标识已被服务端消费：置空防止重复入史（plans/states 保留供结果列表展示）
      analyzedScriptId.value = ''
    } catch {
      toast.notify('脚本执行完成，但汇总历史写入失败。')
    }
  }

  function reset(): void {
    filename.value = ''
    analyzedSourceId.value = ''
    analyzedScriptId.value = ''
    plans.value = []
    states.value = []
    analyzing.value = false
    running.value = false
    cancelRequested.value = false
    lastRunDurationMs.value = null
  }

  return {
    filename,
    plans,
    states,
    analyzing,
    running,
    cancelRequested,
    hasScript,
    needsConfirmationCount,
    progress,
    allSucceeded,
    loadFile,
    run,
    cancelRun,
    reset,
  }
}
