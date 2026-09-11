import { ErrorReason, KRRecord, Kuikly } from '@kuikly-open/render';
import router from '@ohos.router';
import { KuiklyViewDelegate } from '../kuikly/KuiklyViewDelegate';
import globalNativeManager from '../kuikly/MyNativeManager';

/**
 * pushUrl 没有动画入参，转场只能写在页面的 pageTransition。
 * common 侧 Pager.openModule 给同级 Tab 打 transition=fade；下钻不打标。
 *
 * 读的是本实例构造时的 params：出场动画必须用「本页」的标记，不能读栈顶
 * （否则从详情返回时，底下的 Tab 会误用详情页的横切）。
 */
function readFadeFlag(): boolean {
  try {
    const params = router.getParams() as Record<string, Object>;
    if (!params) {
      return false;
    }
    const pageData = params['pageData'] as KRRecord;
    if (!pageData) {
      return false;
    }
    return pageData['transition'] === 'fade';
  } catch (e) {
    return false;
  }
}

@Entry
@Component
struct Index {
  kuiklyViewDelegate = new KuiklyViewDelegate();
  pageName?: string;
  pageData?: KRRecord;
  private navFade: boolean = readFadeFlag();
  @State exception?: string = "";

  aboutToAppear() {
    AppStorage.setOrCreate<UIContext>("context", this.getUIContext());

    const params = router.getParams() as Record<string, Object>;
    this.pageName = params?.pageName as string;
    this.pageData = params?.pageData as KRRecord;
    this.navFade = readFadeFlag();
  }

  pageTransition() {
    if (this.navFade) {
      // 同级模块：新页淡入、旧页保持不透明，避免中段透出桌面。
      // 210ms + FastOutSlowIn 对齐 Android kr_module_fade_in。
      PageTransitionEnter({ type: RouteType.Push, duration: 210, curve: Curve.FastOutSlowIn })
        .opacity(0)
      PageTransitionExit({ type: RouteType.Push, duration: 210, curve: Curve.FastOutSlowIn })
      PageTransitionEnter({ type: RouteType.Pop, duration: 210, curve: Curve.FastOutSlowIn })
      PageTransitionExit({ type: RouteType.Pop, duration: 210, curve: Curve.FastOutSlowIn })
        .opacity(0)
    } else {
      // 层级下钻：iOS push/pop 镜像。进入从右滑入、返回从右滑出。
      // 300ms 对齐 Android kr_slide_*。
      PageTransitionEnter({ type: RouteType.Push, duration: 300, curve: Curve.FastOutSlowIn })
        .slide(SlideEffect.Right)
      PageTransitionExit({ type: RouteType.Push, duration: 300, curve: Curve.FastOutSlowIn })
        .slide(SlideEffect.Left)
      PageTransitionEnter({ type: RouteType.Pop, duration: 300, curve: Curve.FastOutSlowIn })
        .slide(SlideEffect.Left)
      PageTransitionExit({ type: RouteType.Pop, duration: 300, curve: Curve.FastOutSlowIn })
        .slide(SlideEffect.Right)
    }
  }

  build() {
    Stack() {
      Kuikly({
        pagerName: this.pageName ?? 'router',
        pagerData: this.pageData ?? {},
        delegate: this.kuiklyViewDelegate,
        nativeManager: globalNativeManager,
        onRenderException: (exception: Error, reason: ErrorReason) => {
          this.exception = `${exception.name}:\n${exception.message}`;
        },
      })
      if (this.exception) {
        Text(this.exception)
      }
    }
    .width('100%')
    .height('100%')
    .expandSafeArea([SafeAreaType.KEYBOARD])
  }

  onPageShow(): void {
    this.kuiklyViewDelegate.pageDidAppear();
  }

  onPageHide(): void {
    this.kuiklyViewDelegate.pageDidDisappear();
  }
}
