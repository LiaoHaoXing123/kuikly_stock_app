import { ErrorReason, KRRecord, Kuikly } from '@kuikly-open/render';
import router from '@ohos.router';
import { KuiklyViewDelegate } from '../kuikly/KuiklyViewDelegate';
import globalNativeManager from '../kuikly/MyNativeManager';

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

      PageTransitionEnter({ type: RouteType.Push, duration: 210, delay: 120, curve: Curve.FastOutSlowIn })
        .opacity(0)
      PageTransitionExit({ type: RouteType.Push, duration: 90, curve: Curve.FastOutSlowIn })
        .opacity(0)
      PageTransitionEnter({ type: RouteType.Pop, duration: 210, delay: 120, curve: Curve.FastOutSlowIn })
        .opacity(0)
      PageTransitionExit({ type: RouteType.Pop, duration: 90, curve: Curve.FastOutSlowIn })
        .opacity(0)
    } else {

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
