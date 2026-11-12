<script setup lang="ts">
import { ref } from 'vue'
import { ArrowRight, Check, Database, Eye, EyeOff, KeyRound, UserRound } from 'lucide-vue-next'

const emit = defineEmits<{ login: [] }>()
const username = ref('admin')
const password = ref('db-console')
const showPassword = ref(false)
const loading = ref(false)
const error = ref('')

const login = () => {
  error.value = ''
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码。'
    return
  }
  loading.value = true
  window.setTimeout(() => {
    loading.value = false
    emit('login')
  }, 450)
}
</script>

<template>
  <main class="login-page">
    <section class="login-story" aria-labelledby="product-intro">
      <div class="story-brand">
        <span class="brand-symbol"><Database :size="22" :stroke-width="1.8" /></span>
        <span>db-console</span>
      </div>
      <div class="story-copy">
        <p class="story-kicker">数据库工作台</p>
        <h1 id="product-intro">查询、结构与事务，<br />在同一个工作台。</h1>
        <p>连接既定数据源，理解每一次执行的目标、结果与提交状态。</p>
      </div>
      <div class="query-preview" aria-label="数据库查询界面示意">
        <div class="preview-topbar">
          <span class="preview-dot"></span>
          <span class="preview-dot"></span>
          <span class="preview-dot"></span>
          <span class="preview-title">订单生产库 / 查询 1</span>
          <span class="preview-env">生产</span>
        </div>
        <div class="preview-body">
          <div class="preview-schema" aria-hidden="true">
            <span class="schema-root"><Database :size="13" /> commerce</span>
            <span>⌄&nbsp; orders</span>
            <span class="schema-column">id&nbsp; BIGINT</span>
            <span class="schema-column">order_no&nbsp; VARCHAR</span>
            <span>›&nbsp; customers</span>
            <span>›&nbsp; payments</span>
          </div>
          <div class="preview-query">
            <div class="preview-sql mono"><span>SELECT</span> order_no, status, total_amount<br /><span>FROM</span> orders<br /><span>WHERE</span> status = <em>'PAID'</em>;</div>
            <div class="preview-result">
              <div class="preview-result-head mono">order_no <span>status</span></div>
              <div class="preview-result-row mono">SO20261101288 <span><Check :size="12" /> PAID</span></div>
              <div class="preview-result-row mono">SO20261101287 <span><Check :size="12" /> PAID</span></div>
            </div>
          </div>
        </div>
        <div class="preview-footer"><span class="status-light"></span> 连接正常 <span>手动事务</span></div>
      </div>
      <p class="story-foot">MySQL · DM8 · JDBC</p>
    </section>

    <section class="login-panel" aria-labelledby="login-title">
      <div class="login-form-wrap">
        <div class="mobile-brand"><Database :size="21" /> db-console</div>
        <p class="login-overline">欢迎回来</p>
        <h2 id="login-title">登录工作台</h2>
        <p class="login-description">使用部署配置中的应用账号登录。</p>
        <form class="login-form" method="post" action="#workspace" @submit.prevent="login">
          <div class="form-field">
            <label for="username">用户名</label>
            <div class="input-shell">
              <UserRound :size="17" aria-hidden="true" />
              <input id="username" v-model="username" name="username" type="text" autocomplete="username" autocorrect="off" autocapitalize="none" spellcheck="false" required enterkeyhint="next" @input="error = ''" />
            </div>
          </div>
          <div class="form-field">
            <label for="password">密码</label>
            <div class="input-shell">
              <KeyRound :size="17" aria-hidden="true" />
              <input id="password" v-model="password" name="password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" required enterkeyhint="done" aria-describedby="login-error" @input="error = ''" />
              <button class="password-toggle" type="button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" :aria-pressed="showPassword" @click="showPassword = !showPassword">
                <EyeOff v-if="showPassword" :size="17" />
                <Eye v-else :size="17" />
              </button>
            </div>
          </div>
          <p v-if="error" id="login-error" class="login-error" role="alert">{{ error }}</p>
          <button class="login-submit" type="submit" :disabled="loading">
            {{ loading ? '正在登录…' : '登录工作台' }}
            <ArrowRight v-if="!loading" :size="18" />
          </button>
        </form>
        <div class="prototype-note">
          <span>界面原型</span>
          已填入演示账号，可直接登录。
        </div>
        <p class="security-note">数据库密码由服务端环境变量注入，<br />不会在浏览器中配置或展示。</p>
      </div>
    </section>
  </main>
</template>

<style scoped>
.login-page {
  min-block-size: 100dvh;
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(400px, 0.8fr);
  background: #ffffff;
}

.login-story {
  position: relative;
  padding: 42px clamp(40px, 6vw, 96px) 30px;
  color: #eff8f4;
  background: #164f49;
  display: flex;
  flex-direction: column;
  overflow: clip;
}

.login-story::before {
  content: '';
  position: absolute;
  inset: 0;
  opacity: 0.08;
  pointer-events: none;
  background-image: linear-gradient(#d9eee6 1px, transparent 1px), linear-gradient(90deg, #d9eee6 1px, transparent 1px);
  background-size: 36px 36px;
}

.story-brand,
.mobile-brand {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 19px;
  font-weight: 650;
  letter-spacing: -0.03em;
}

.brand-symbol {
  inline-size: 36px;
  block-size: 36px;
  border: 1px solid #8dbbb0;
  border-radius: 8px;
  display: grid;
  place-items: center;
}

.story-copy {
  position: relative;
  margin-block-start: clamp(56px, 10vh, 110px);
}

.story-kicker,
.login-overline {
  margin: 0 0 14px;
  color: #a9cec2;
  font-size: 13px;
  font-weight: 560;
}

.story-copy h1 {
  margin: 0;
  max-inline-size: 620px;
  font-size: clamp(34px, 4vw, 58px);
  font-weight: 570;
  line-height: 1.18;
  letter-spacing: -0.045em;
}

.story-copy > p:last-child {
  max-inline-size: 480px;
  margin: 22px 0 0;
  color: #c1d9d1;
  font-size: 16px;
  line-height: 1.8;
}

.query-preview {
  position: relative;
  inline-size: min(680px, 100%);
  margin-block-start: clamp(38px, 7vh, 72px);
  border: 1px solid #7ba99c;
  border-radius: 8px;
  overflow: clip;
  color: #233b35;
  background: #f8fbf9;
  box-shadow: 18px 24px 0 #0e403b;
}

.preview-topbar {
  min-block-size: 39px;
  padding-inline: 14px;
  display: flex;
  align-items: center;
  gap: 6px;
  border-block-end: 1px solid #d7e1dc;
  background: #ffffff;
  font-size: 11px;
}

.preview-dot {
  inline-size: 7px;
  block-size: 7px;
  border-radius: 50%;
  background: #bdc9c3;
}

.preview-title {
  margin-inline-start: 8px;
}

.preview-env {
  margin-inline-start: auto;
  padding: 2px 5px;
  color: #a03c36;
  border-radius: 3px;
  background: #fbeceb;
}

.preview-body {
  min-block-size: 190px;
  display: grid;
  grid-template-columns: 155px 1fr;
}

.preview-schema {
  padding: 18px 14px;
  border-inline-end: 1px solid #d7e1dc;
  display: flex;
  flex-direction: column;
  gap: 12px;
  font-size: 11px;
}

.schema-root {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}

.schema-column {
  padding-inline-start: 20px;
  color: #71817a;
  font-family: var(--code-font);
  font-size: 9px;
}

.preview-query {
  min-inline-size: 0;
}

.preview-sql {
  min-block-size: 102px;
  padding: 18px;
  border-block-end: 1px solid #d7e1dc;
  font-size: 11px;
  line-height: 1.9;
}

.preview-sql span {
  color: #176b63;
  font-weight: 500;
}

.preview-sql em {
  color: #a36526;
  font-style: normal;
}

.preview-result {
  padding: 9px 18px;
  font-size: 10px;
}

.preview-result-head,
.preview-result-row {
  min-block-size: 23px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}

.preview-result-head {
  color: #71817a;
}

.preview-result-row span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #327359;
}

.preview-footer {
  min-block-size: 29px;
  padding-inline: 14px;
  border-block-start: 1px solid #d7e1dc;
  display: flex;
  align-items: center;
  gap: 6px;
  color: #66766f;
  background: #ffffff;
  font-size: 10px;
}

.preview-footer > span:last-child {
  margin-inline-start: auto;
}

.status-light {
  inline-size: 6px;
  block-size: 6px;
  border-radius: 50%;
  background: #327359;
}

.story-foot {
  position: relative;
  margin: auto 0 0;
  padding-block-start: 36px;
  color: #a9cec2;
  font-family: var(--code-font);
  font-size: 11px;
}

.login-panel {
  display: grid;
  place-items: center;
  padding: 48px clamp(28px, 5vw, 80px);
}

.login-form-wrap {
  inline-size: min(370px, 100%);
}

.mobile-brand {
  display: none;
}

.login-overline {
  color: var(--accent);
}

.login-panel h2 {
  margin: 0;
  font-size: 32px;
  font-weight: 600;
  letter-spacing: -0.035em;
}

.login-description {
  margin: 12px 0 34px;
  color: var(--muted);
  line-height: 1.6;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 21px;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form-field label {
  font-weight: 560;
}

.input-shell {
  min-block-size: 48px;
  padding-inline: 14px;
  border: 1px solid var(--line-strong);
  border-radius: 6px;
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--muted);
  background: #ffffff;
}

.input-shell:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}

.input-shell input {
  min-inline-size: 0;
  min-block-size: 46px;
  padding: 0;
  border: 0;
  outline: 0;
  color: var(--ink);
  background: transparent;
  flex: 1;
  font-size: max(1rem, 1em);
}

.password-toggle {
  inline-size: 36px;
  block-size: 40px;
  padding: 0;
  border: 0;
  display: grid;
  place-items: center;
  color: var(--muted);
  background: transparent;
}

.login-submit {
  min-block-size: 48px;
  margin-block-start: 3px;
  padding-inline: 17px;
  border: 1px solid var(--accent);
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #ffffff;
  background: var(--accent);
  font-weight: 560;
}

.login-submit:hover:not(:disabled) {
  background: var(--accent-hover);
}

.login-error {
  margin: -8px 0 0;
  color: var(--danger);
}

.prototype-note {
  margin-block-start: 24px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 5px;
  color: var(--muted);
  background: var(--surface-subtle);
  font-size: 12px;
  line-height: 1.6;
}

.prototype-note span {
  margin-inline-end: 7px;
  color: var(--accent);
  font-weight: 600;
}

.security-note {
  margin-block-start: 30px;
  color: var(--faint);
  font-size: 12px;
  line-height: 1.7;
}

@media (max-width: 960px) {
  .login-page {
    grid-template-columns: minmax(0, 1fr) minmax(360px, 0.85fr);
  }

  .login-story {
    padding-inline: 36px;
  }

  .story-copy h1 {
    font-size: 36px;
  }

  .preview-schema {
    display: none;
  }

  .preview-body {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .login-page {
    display: block;
  }

  .login-story {
    display: none;
  }

  .login-panel {
    min-block-size: 100dvh;
    padding: 32px 24px;
  }

  .mobile-brand {
    margin-block-end: 64px;
    display: flex;
    color: var(--accent);
  }
}
</style>
