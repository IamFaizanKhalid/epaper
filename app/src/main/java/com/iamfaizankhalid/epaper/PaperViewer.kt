package com.iamfaizankhalid.epaper

interface PaperViewer {
    fun renderLoadingPage(page: NewspaperPage): String

    fun renderPage(
        page: NewspaperPage,
        hotspots: List<NewspaperHotspot>,
        paper: Paper,
        directory: java.io.File
    ): String

    fun renderArticle(
        edition: NewspaperEdition,
        article: NewspaperArticle,
        paper: Paper,
        directory: java.io.File?,
        textScale: Float = 1f,
        darkTheme: Boolean = false
    ): String
}

class WebPaperViewer : PaperViewer {
    override fun renderLoadingPage(page: NewspaperPage): String =
        "<html><body style='font:18px sans-serif;padding:24px'>Loading ${escape(page.title)}…</body></html>"

    override fun renderPage(
        page: NewspaperPage,
        hotspots: List<NewspaperHotspot>,
        paper: Paper,
        directory: java.io.File
    ): String {
        val imageFileName = if (java.io.File(directory, "${page.id}.jpg").isFile) {
            "${page.id}.jpg"
        } else {
            "${page.id}.png"
        }
        val hotspotData = org.json.JSONArray().apply {
            hotspots.forEach { hotspot ->
                put(org.json.JSONObject().apply {
                    put("shape", hotspot.shape)
                    put("coordinates", org.json.JSONArray(hotspot.coordinates))
                    put("coordinateWidth", hotspot.coordinateWidth)
                    put("coordinateHeight", hotspot.coordinateHeight)
                    put("route", if (hotspot.type == NewspaperHotspotType.TEXT) "text" else "image")
                    put("targetId", hotspot.targetId)
                    put("label", hotspot.label)
                })
            }
        }
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,minimum-scale=1,maximum-scale=5,user-scalable=yes">
            <style>body{margin:0;background:#222;text-align:center}
            a,img{-webkit-tap-highlight-color:transparent;outline:none;-webkit-user-select:none;user-select:none}
            #paper-container{position:relative;width:100%}
            #page{display:block;width:100%;height:auto;margin:auto;background:#fff;-webkit-user-drag:none}
            #hotspots{position:absolute;inset:0}
            #hotspots a{position:absolute;display:block;color:transparent;outline:none}
            .hotspot-highlight{position:absolute;z-index:2;pointer-events:none;
              background:rgba(255,210,0,.32);border:3px solid #ffd400;box-sizing:border-box;
              box-shadow:0 0 12px rgba(255,212,0,.9)}</style>
            </head><body><div id="paper-container"><img id="page" src="$imageFileName"><div id="hotspots"></div></div>
            <script>
            (function(){const img=document.getElementById('page');
            const layer=document.getElementById('hotspots');
            window.updatePageHotspots=function(items){layer.replaceChildren();
            if(!img.naturalWidth||!img.naturalHeight)return;
            items.forEach(item=>{const coords=item.coordinates;
            const shape=item.shape.toLowerCase();
            const coordinateWidth=Number(item.coordinateWidth)||img.naturalWidth;
            const coordinateHeight=Number(item.coordinateHeight)||img.naturalHeight;
            const link=document.createElement('a');
            link.href='${paper.offlineOrigin}'+item.route+'/'+encodeURIComponent(item.targetId);
            link.setAttribute('aria-label',item.label||'Open story');
            if(shape==='circle'&&coords.length>=3){
            const x=coords[0],y=coords[1],r=coords[2];
            link.style.left=(100*(x-r)/coordinateWidth)+'%';
            link.style.top=(100*(y-r)/coordinateHeight)+'%';
            link.style.width=(200*r/coordinateWidth)+'%';
            link.style.height=(200*r/coordinateHeight)+'%';
            link.style.borderRadius='50%';
            }else if(shape==='rect'&&coords.length>=4){
            const left=Math.min(coords[0],coords[2]),right=Math.max(coords[0],coords[2]);
            const top=Math.min(coords[1],coords[3]),bottom=Math.max(coords[1],coords[3]);
            link.style.left=(100*left/coordinateWidth)+'%';
            link.style.top=(100*top/coordinateHeight)+'%';
            link.style.width=(100*(right-left)/coordinateWidth)+'%';
            link.style.height=(100*(bottom-top)/coordinateHeight)+'%';
            }else if(coords.length>=6){
            const points=[];
            for(let i=0;i+1<coords.length;i+=2)
            points.push((100*coords[i]/coordinateWidth)+'% '+(100*coords[i+1]/coordinateHeight)+'%');
            link.style.inset='0';
            link.style.clipPath='polygon('+points.join(',')+')';
            }else{return}
            link.dataset.shape=shape;
            link.dataset.coordinates=coords.join(',');
            link.dataset.coordinateWidth=String(coordinateWidth);
            link.dataset.coordinateHeight=String(coordinateHeight);
            layer.appendChild(link);
            })};
            function renderHotspots(){window.updatePageHotspots($hotspotData)};
            layer.addEventListener('click',function(event){
            const link=event.target.closest('a');if(!link)return;
            const shape=link.dataset.shape,coords=link.dataset.coordinates.split(',').map(Number);
            event.preventDefault();
            let left,top,right,bottom;
            if(shape==='circle'){
            left=coords[0]-coords[2];top=coords[1]-coords[2];
            right=coords[0]+coords[2];bottom=coords[1]+coords[2]}
            else{const xs=coords.filter((_,i)=>i%2===0),ys=coords.filter((_,i)=>i%2===1);
            left=Math.min(...xs);top=Math.min(...ys);right=Math.max(...xs);bottom=Math.max(...ys)}
            const mark=document.createElement('div');mark.className='hotspot-highlight';
            const coordinateWidth=Number(link.dataset.coordinateWidth)||img.naturalWidth;
            const coordinateHeight=Number(link.dataset.coordinateHeight)||img.naturalHeight;
            const sx=img.clientWidth/coordinateWidth,sy=img.clientHeight/coordinateHeight;
            mark.style.left=(left*sx)+'px';mark.style.top=(top*sy)+'px';
            mark.style.width=((right-left)*sx)+'px';mark.style.height=((bottom-top)*sy)+'px';
            if(shape==='circle')mark.style.borderRadius='50%';
            document.getElementById('paper-container').appendChild(mark);const target=link.href;
            setTimeout(()=>{location.href=target},180)});
            img.addEventListener('load',renderHotspots);
            if(img.complete)renderHotspots();
            new ResizeObserver(renderHotspots).observe(img);
            })();
            </script></body></html>
        """.trimIndent()
    }

    override fun renderArticle(
        edition: NewspaperEdition,
        article: NewspaperArticle,
        paper: Paper,
        directory: java.io.File?,
        textScale: Float,
        darkTheme: Boolean
    ): String {
        val paragraphs = article.paragraphs.joinToString("") {
            "<p>${escape(it)}</p>"
        }
        val images = article.imageUrls.mapIndexed { index, imageUrl ->
            val localImage = directory?.let {
                edition.articleImageFile(it, article, index)
            }?.takeIf { it.isFile }?.let {
                paper.localArticleImageUrl(article.id, index)
            }
            val image = """<img src="${escape(localImage ?: imageUrl)}" alt="" loading="lazy">"""
            val target = if (paper.supportsInlineArticleImages) {
                paper.inlineArticleImageTarget(article.id, index)
            } else {
                null
            }
            if (target != null) {
                """<a href="${escape(target)}">$image</a>"""
            } else {
                image
            }
        }.joinToString("")
        val fontFace = if (paper.articleFontFamily.contains("AliNastaliq")) {
            """
                @font-face{font-family:'AliNastaliq';src:url('file:///android_asset/fonts/ali_nastaliq.woff') format('woff');font-weight:400;font-style:normal}
            """.trimIndent()
        } else {
            ""
        }
        val headingLineHeight = if (paper.articleTextDirection == "rtl") "1.8" else "1.2"
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>$fontFace
            :root{--article-font-scale:$textScale}
            html,body{background:${if (darkTheme) "#121212" else "#fff"}}
            body{max-width:760px;margin:24px auto;padding:0 20px;
            font-family:${paper.articleFontFamily};font-size:calc(${if (paper.articleTextDirection == "rtl") 22 else 18}px * var(--article-font-scale));
            line-height:${if (paper.articleTextDirection == "rtl") "2.1" else "1.65"};
            background:${if (darkTheme) "#121212" else "#fff"};
            color:${if (darkTheme) "#f1f1f1" else "#222"};
            direction:${paper.articleTextDirection};text-align:start}
            h1{font-size:${if (paper.articleTextDirection == "rtl") "1.36em" else "1.67em"};line-height:$headingLineHeight}
            .byline{color:${if (darkTheme) "#aaa" else "#666"};font-size:.85em;margin:12px 0 24px}
            p{margin:0 0 1em}img{display:block;max-width:100%;height:auto;margin:20px auto}
            img.story-image{cursor:zoom-in}
            #image-viewer{display:none;position:fixed;inset:0;z-index:1000;background:rgba(0,0,0,.96);
            align-items:center;justify-content:center;overflow:auto;padding:16px;touch-action:none}
            #image-viewer.open{display:flex}
            #image-viewer img{display:block;max-width:none;width:auto;height:auto;margin:0;
            transform-origin:center;touch-action:none}
            #image-viewer .close{position:fixed;right:18px;top:18px;width:44px;height:44px;
            border:0;border-radius:22px;background:#fff;color:#111;font:28px Arial;z-index:1001}
            .image-button{display:inline-block;margin:8px 0 18px;padding:10px 16px;
            border-radius:4px;background:#244d70;color:#fff;text-decoration:none;font:14px Arial,sans-serif}
            </style></head><body><h1>${escape(article.title)}</h1>
            <div class="byline">${escape(article.byline)}</div>$images$paragraphs
            <div id="image-viewer" role="dialog" aria-label="Zoomed article image">
            <button class="close" aria-label="Close image">&times;</button><img alt=""></div>
            <script>
            (() => {
              const viewer = document.getElementById('image-viewer');
              const zoomed = viewer.querySelector('img');
              let scale = 1, startDistance = 0, startScale = 1;
              let panX = 0, panY = 0, startPanX = 0, startPanY = 0;
              let lastTouchX = 0, lastTouchY = 0;
              const applyTransform = () => {
                zoomed.style.transform = 'translate(' + panX + 'px,' + panY + 'px) scale(' + scale + ')';
              };
              const resetImage = () => {scale = 1; panX = 0; panY = 0; applyTransform();};
              const closeViewer = () => {viewer.classList.remove('open'); resetImage();};
              const openViewer = source => {
                zoomed.src = source; scale = 1; panX = 0; panY = 0; zoomed.style.transform = '';
                viewer.classList.add('open');
                zoomed.onload = () => {
                  const fittedWidth = Math.min(zoomed.naturalWidth, viewer.clientWidth - 32);
                  zoomed.style.maxWidth = fittedWidth + 'px';
                  resetImage();
                };
              };
              document.querySelectorAll('img').forEach((image, index) => {
                image.classList.add('story-image');
                image.setAttribute('tabindex', '0');
                image.addEventListener('click', () => openViewer(image.currentSrc || image.src));
                image.addEventListener('keydown', event => {
                  if (event.key === 'Enter' || event.key === ' ') openViewer(image.currentSrc || image.src);
                });
              });
              viewer.querySelector('.close').addEventListener('click', closeViewer);
              viewer.addEventListener('click', event => {if (event.target === viewer) closeViewer();});
              const distance = touches => Math.hypot(
                touches[0].clientX - touches[1].clientX,
                touches[0].clientY - touches[1].clientY);
              viewer.addEventListener('touchstart', event => {
                if (window.StoryImageTouch) StoryImageTouch.beginTouch();
                if (event.touches.length === 2) {
                  startDistance = distance(event.touches); startScale = scale;
                }
                if (event.touches.length === 1 && scale > 1) {
                  lastTouchX = event.touches[0].clientX;
                  lastTouchY = event.touches[0].clientY;
                  startPanX = panX; startPanY = panY;
                }
              }, {passive: true});
              viewer.addEventListener('touchmove', event => {
                event.preventDefault();
                if (event.touches.length === 2 && startDistance) {
                  scale = Math.min(5, Math.max(1, startScale * distance(event.touches) / startDistance));
                  applyTransform();
                } else if (event.touches.length === 1 && scale > 1) {
                  panX = startPanX + event.touches[0].clientX - lastTouchX;
                  panY = startPanY + event.touches[0].clientY - lastTouchY;
                  applyTransform();
                }
              }, {passive: false});
              viewer.addEventListener('touchend', event => {
                if (window.StoryImageTouch) StoryImageTouch.endTouch();
                if (event.touches.length === 0 && scale === 1) {
                  panX = 0; panY = 0; applyTransform();
                }
              }, {passive: true});
              zoomed.addEventListener('dblclick', event => {
                event.preventDefault();
                resetImage();
              });
            })();
            </script></body></html>
        """.trimIndent()
    }

    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
}
