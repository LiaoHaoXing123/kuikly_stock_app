import { IKRRouterAdapter, KRRecord } from '@kuikly-open/render';
import router from '@ohos.router';
import common from '@ohos.app.ability.common';

/**
 * 鸿蒙 router.pushUrl 没有动画入参，fade / 从右滑入 都在 pages/Index.pageTransition
 * 里按 pageData.transition 分支。这里只负责把 pageName + pageData 压进栈。
 */
export class RouterAdapter implements IKRRouterAdapter {
  openPage(context: common.UIAbilityContext, pageName: string, pageData: KRRecord): void {
    router.pushUrl({
      url: 'pages/Index',
      params: {
        pageName,
        pageData
      }
    })
  }

  closePage(context: common.UIAbilityContext): void {
    router.back()
  }
}
