package com.silk.web

import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.renderComposable
import org.jetbrains.compose.web.css.*

// 最简单的测试版本
fun mainTest() {
    console.log("🧪 测试版本启动...")
    
    renderComposable(rootElementId = "root") {
        Div({
            style {
                padding(20.px)
                fontSize(24.px)
                textAlign("center")
                backgroundColor(Color("#f0f0f0"))
            }
        }) {
            H1 {
                Text("✅ Silk Web UI 测试")
            }
            
            P {
                Text("如果您看到这个页面，说明基础渲染工作正常")
            }
            
            Button({
                style {
                    padding(12.px, 24.px)
                    fontSize(16.px)
                    backgroundColor(Color("#1976d2"))
                    color(Color.white)
                    border { width(0.px) }
                    borderRadius(4.px)
                    property("cursor", "pointer")
                    marginTop(20.px)
                }
                onClick {
                    console.log("✅ 按钮点击正常")
                    kotlinx.browser.window.alert("测试成功！")
                }
            }) {
                Text("测试按钮")
            }
        }
    }
    
    console.log("✅ 测试版本渲染完成")
}

