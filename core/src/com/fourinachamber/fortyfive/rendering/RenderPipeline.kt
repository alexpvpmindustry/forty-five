package com.fourinachamber.fortyfive.rendering

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL20.GL_BLEND
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.TimeUtils
import com.fourinachamber.fortyfive.game.GraphicsConfig
import com.fourinachamber.fortyfive.screen.ResourceHandle
import com.fourinachamber.fortyfive.screen.ResourceManager
import com.fourinachamber.fortyfive.screen.general.OnjScreen
import com.fourinachamber.fortyfive.utils.Timeline

interface Renderable {

    fun init() { }

    // TODO: differentiate between renderables that can be rendered to a fbo and those who can not
    fun render(delta: Float)
}

// TODO: this could use just one shader wich does everything
class GameRenderPipeline(private val screen: OnjScreen) : Renderable {

    private val currentPostProcessingShaders = mutableListOf<BetterShader>()

    private var sizeDirty = false

    private var fadeToBlack: Float = 0.0f

    private var parryShader: BetterShader? = null

    override fun init() {
    }

    override fun render(delta: Float) {
        if (sizeDirty) {
            screen.resize(Gdx.graphics.width, Gdx.graphics.height)
            sizeDirty = false
        }
        if (fadeToBlack != 0f && currentPostProcessingShaders.isNotEmpty()) {
            currentPostProcessingShaders.clear()
        }
        if (currentPostProcessingShaders.isEmpty()) {
            screen.render(delta)
            if (fadeToBlack != 0f) {
                val renderer = ShapeRenderer()
                renderer.begin(ShapeType.Filled)
                Gdx.gl.glEnable(GL20.GL_BLEND)
                Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
                renderer.color = Color(0f, 0f, 0f, fadeToBlack)
                renderer.rect(
                    0f, 0f,
                    Gdx.graphics.width.toFloat(),
                    Gdx.graphics.height.toFloat(),
                )
                renderer.end()
                renderer.dispose()
            }
            return
        }

        val fbo = try {
            FrameBuffer(Pixmap.Format.RGBA8888, Gdx.graphics.width, Gdx.graphics.height, false)
        } catch (e: java.lang.IllegalStateException) {
            // construction of FrameBuffer sometimes fails when the window is minimized
            return
        }

        ScreenUtils.clear(0.0f, 0.0f, 0.0f, 1.0f)
        fbo.begin()
        screen.stage.viewport.apply()
        screen.render(delta)
        fbo.end()

        currentPostProcessingShaders.forEachIndexed { index, shader ->
            val last = index == currentPostProcessingShaders.size - 1

            // TODO: this is either really smart or really stupid
            // TODO: this seems smart now but i bet it will blow up later

            val batch = SpriteBatch()
            if (!last) fbo.begin()
            batch.shader = shader.shader
            shader.shader.bind()
            shader.prepare(screen)
            batch.begin()
            batch.enableBlending()
            batch.draw(
                fbo.colorBufferTexture,
                0f, 0f,
                Gdx.graphics.width.toFloat(),
                Gdx.graphics.height.toFloat(),
                0f, 0f, 1f, 1f // flips the y-axis
            )
            batch.end()
            if (!last) fbo.end()
            batch.dispose()

        }
        fbo.dispose()
    }

    fun getOnDeathPostProcessingTimelineAction(): Timeline.TimelineAction = Timeline.timeline {

        includeAction(fadeToBlackTimelineAction())
        action { this@GameRenderPipeline.fadeToBlack = 1f }
        delay(2000)

    }.asAction()

    private fun fadeToBlackTimelineAction(): Timeline.TimelineAction {
        val duration = 2000
        return object : Timeline.TimelineAction() {

            var finishesAt: Long = -1

            override fun start(timeline: Timeline) {
                super.start(timeline)
                finishesAt = TimeUtils.millis() + duration
            }

            override fun isFinished(timeline: Timeline): Boolean {
                val now = TimeUtils.millis()
                val remaining = (finishesAt - now).toFloat()
                this@GameRenderPipeline.fadeToBlack = 1f - remaining / duration.toFloat()
                return now >= finishesAt
            }

            override fun end(timeline: Timeline) {
                super.end(timeline)
                this@GameRenderPipeline.fadeToBlack = 0f
            }
        }
    }

    fun getOnShotPostProcessingTimelineAction(): Timeline.TimelineAction {
        val shader = GraphicsConfig.shootShader(screen)
        val duration = GraphicsConfig.shootPostProcessingDuration()
        return object : Timeline.TimelineAction() {

            var finishesAt: Long = -1

            override fun start(timeline: Timeline) {
                super.start(timeline)
                finishesAt = TimeUtils.millis() + duration
                currentPostProcessingShaders.add(shader)
                shader.resetReferenceTime()
                sizeDirty = true
            }

            override fun isFinished(timeline: Timeline): Boolean = TimeUtils.millis() >= finishesAt

            override fun end(timeline: Timeline) {
                super.end(timeline)
                currentPostProcessingShaders.remove(shader)
                sizeDirty = true
            }
        }
    }

    fun startParryEffect(screen: OnjScreen) {
        val parryShader = parryShader ?: ResourceManager.get(screen, "parry_shader")
        this.parryShader = parryShader
        currentPostProcessingShaders.add(parryShader)
    }

    fun stopParryEffect() {
        currentPostProcessingShaders.remove(parryShader)
    }

}

class PostProcessedRenderPipeline(
    private val screen: OnjScreen,
    private val postProcessorHandle: ResourceHandle,
    private val shaderPreparer: ((shader: BetterShader) -> Unit)? = null
) : Renderable {

    private val postProcessor: BetterShader by lazy {
        ResourceManager.get(screen, postProcessorHandle)
    }

    override fun render(delta: Float) {
        val fbo = try {
            FrameBuffer(Pixmap.Format.RGBA8888, Gdx.graphics.width, Gdx.graphics.height, false)
        } catch (e: java.lang.IllegalStateException) {
            // construction of FrameBuffer sometimes fails when the window is minimized
            return
        }

        ScreenUtils.clear(0.0f, 0.0f, 0.0f, 1.0f)
        fbo.begin()
        screen.stage.viewport.apply()
        screen.render(delta)
        if (!screen.isVisible) {
            // this has to be checked here because isVisible is set to false inside the render function
            // TODO: fix this
            fbo.end()
            fbo.dispose()
            return
        }
        fbo.end()
        val batch = SpriteBatch()
        postProcessor.shader.bind()
        postProcessor.prepare(screen)
        batch.shader = postProcessor.shader
        shaderPreparer?.let { it(postProcessor) }
        batch.begin()
        batch.enableBlending()
        batch.draw(
            fbo.colorBufferTexture,
            0f, 0f,
            Gdx.graphics.width.toFloat(),
            Gdx.graphics.height.toFloat(),
            0f, 0f, 1f, 1f // flips the y-axis
        )
        batch.end()
        fbo.dispose()
    }
}
