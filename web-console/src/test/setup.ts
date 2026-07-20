/**
 * Vitest 全局测试 setup。
 *
 * 职责：注册 @testing-library/jest-dom 的 DOM 断言扩展（如 toBeInTheDocument），
 * 使组件测试可使用语义化 matcher 而不仅依赖 textContent 比较。
 *
 * 副作用：本文件在 vitest.config 中被 preload，每个测试文件运行前执行一次。
 */
import '@testing-library/jest-dom/vitest'
