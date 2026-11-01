package com.faizan.epaper

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
            const link=document.createElement('a');
            link.href='${paper.offlineOrigin}'+item.route+'/'+encodeURIComponent(item.targetId);
            link.setAttribute('aria-label',item.label||'Open story');
            if(shape==='circle'&&coords.length>=3){
            const x=coords[0],y=coords[1],r=coords[2];
            link.style.left=(100*(x-r)/img.naturalWidth)+'%';
            link.style.top=(100*(y-r)/img.naturalHeight)+'%';
            link.style.width=(200*r/img.naturalWidth)+'%';
            link.style.height=(200*r/img.naturalHeight)+'%';
            link.style.borderRadius='50%';
            }else if(shape==='rect'&&coords.length>=4){
            const left=Math.min(coords[0],coords[2]),right=Math.max(coords[0],coords[2]);
            const top=Math.min(coords[1],coords[3]),bottom=Math.max(coords[1],coords[3]);
            link.style.left=(100*left/img.naturalWidth)+'%';
            link.style.top=(100*top/img.naturalHeight)+'%';
            link.style.width=(100*(right-left)/img.naturalWidth)+'%';
            link.style.height=(100*(bottom-top)/img.naturalHeight)+'%';
            }else if(coords.length>=6){
            const points=[];
            for(let i=0;i+1<coords.length;i+=2)
            points.push((100*coords[i]/img.naturalWidth)+'% '+(100*coords[i+1]/img.naturalHeight)+'%');
            link.style.inset='0';
            link.style.clipPath='polygon('+points.join(',')+')';
            }else{return}
            link.dataset.shape=shape;
            link.dataset.coordinates=coords.join(',');
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
            const sx=img.clientWidth/img.naturalWidth,sy=img.clientHeight/img.naturalHeight;
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
        val imageButton = paper.articleImageDetailTarget(article.id)?.let { target ->
            """
                <a class="image-button" href="${escape(target)}">Display as image</a>
            """.trimIndent()
        }.orEmpty()
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
            .image-button{display:inline-block;margin:8px 0 18px;padding:10px 16px;
            border-radius:4px;background:#244d70;color:#fff;text-decoration:none;font:14px Arial,sans-serif}
            </style></head><body><h1>${escape(article.title)}</h1>
            <div class="byline">${escape(article.byline)}</div>$imageButton$images$paragraphs</body></html>
        """.trimIndent()
    }

    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
}
