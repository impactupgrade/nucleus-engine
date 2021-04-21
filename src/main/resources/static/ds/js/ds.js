var ds = new function () {
  let primary_color = "#3399CC";
  this.ds_submitted = false;

  function loadScript(src, callback ) {
    var script = document.createElement("script");
    script.setAttribute("src", src);
    script.addEventListener("load", callback);
    var firstScriptTag = document.getElementsByTagName('script')[0];
    firstScriptTag.parentNode.insertBefore(script, firstScriptTag);
  };

  this.init = function (args) {
    args.ds_api_key = "REPLACE_ME_DS_API_KEY";
    args.stripe_public_key = "REPLACE_ME_STRIPE_PUBLIC_KEY";

    primary_color = args.primary_color || primary_color;
    toHSL(primary_color);

    loadScript("https://js.stripe.com/v3/", function () {
      var ds_displays = document.querySelectorAll('[data-ds-display]');
      for (var i in ds_displays) {
        if (ds_displays.hasOwnProperty(i)) {
          switch (ds_displays[i].getAttribute('data-ds-display')) {
            case "button":
              ds.options = args;
              addbutton();
              addform_iframe();
              //addreminder();
              break;
            case "form":
              addform_inline(args);
              break;
          }
        }
      }
    });
  }

  this.showform = function () {
    form = document.getElementById('DSFORM');
    document.body.style.overflow = "hidden"
    form.style.display = "block";
    form.focus();
    form.addEventListener('focusout', function (event) {
      event.preventDefault();
      event.stopPropagation();
      form.focus();
    });
    //ds.hidereminder();
  };

  this.hideform = function () {
    document.body.style.overflow = "inherit"
    document.getElementById('DSFORM').style.display = "none";
  /*  if (!ds.ds_submitted){
      ds.showreminder();
    } */
  };

  this.showreminder = function () {
    document.getElementById('DSREMIND').style.display = "block";
  };

  this.hidereminder = function () {
    document.getElementById('DSREMIND').style.display = "none";
  };

  addbutton = function () {
    document.querySelector('[data-ds-display="button"]').innerHTML += '<iframe width="125" title="Donation Spring" height="40" src="https://nucleus.impactupgrade.com/ds/button.html" frameborder="0" scrolling="no" marginheight="0" marginwidth="0" style="visibility: visible; display: inline-block !important; vertical-align: middle !important; width: 125px !important; min-width: 40px !important; max-width: 125px !important; height: 40px !important; min-height: 40px !important; max-height: 125px !important;" id="DSDB"></iframe>'
  };



  addform_inline = function (args) {
      loadScript("https://nucleus.impactupgrade.com/ds/js/donationspring.js", function () {
        loadHTML();
      });
    function loadHTML(){
      var xhttp = new XMLHttpRequest();
      xhttp.onreadystatechange = function () {
        if (xhttp.readyState == XMLHttpRequest.DONE) {
          if (xhttp.status == 200) {
            document.querySelector('[data-ds-display="form"]').innerHTML = this.responseText;
            donationspring.init(args);
          }
        }
      };
      xhttp.open("GET", "https://nucleus.impactupgrade.com/ds/form_inline.html", true);
      xhttp.send(); 
    }
  }

  addform_iframe = function() {
    dsform = document.createElement('iframe');
    dsform.setAttribute('id', 'DSFORM');
    dsform.setAttribute('frameborder', '0');
    dsform.setAttribute('title', 'Donation Spring Form');
    dsform.setAttribute('style', 'display: none; margin: 0 !important;padding: 0 !important;border: 0 !important; width: 100% !important; height: 100% !important; position: fixed !important; opacity: 1 !important; top: 0 !important; left: 0 !important; right: 0 !important; bottom: 0 !important; z-index: 2147483646 !important;');
    dsform.setAttribute('src', 'https://nucleus.impactupgrade.com/ds/form_iframe.html');
    dsform.setAttribute('onload', 'this.contentWindow.focus()');
    document.body.appendChild(dsform);
  };

/*  addreminder = function () {
    dsreminder = document.createElement('iframe');
    dsreminder.setAttribute('id', 'DSREMIND');
    dsreminder.setAttribute('frameborder', '0');
    dsreminder.setAttribute('scrolling', 'no');
    dsreminder.setAttribute('marginheight', '0');
    dsreminder.setAttribute('marginwidth', '0');
    dsreminder.setAttribute('title', 'Donation Spring Reminder');
    dsreminder.setAttribute('style', 'display: none !important; visibility: visible; !important; opacity: 1 !important; inset: auto 10px 10px auto !important; position: fixed !important; z-index: 2147483644 !important; margin: 0px !important; padding: 0px !important; height: 110px !important; min-height: 110px !important; max-height: 110px !important; width: 425px !important; min-width: 425px !important; max-width: 425px !important;');
    dsreminder.setAttribute('src', 'https://nucleus.impactupgrade.com/ds/reminder.html');
    document.body.appendChild(dsreminder);
  }; */

  toHSL = function (hex) {
    var result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex);
    var r = parseInt(result[1], 16);
    var g = parseInt(result[2], 16);
    var b = parseInt(result[3], 16);
    r /= 255, g /= 255, b /= 255;
    var max = Math.max(r, g, b), min = Math.min(r, g, b);
    var h, s, l = (max + min) / 2;
    if (max == min) {
      h = s = 0; // achromatic
    } else {
      var d = max - min;
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      switch (max) {
        case r: h = (g - b) / d + (g < b ? 6 : 0); break;
        case g: h = (b - r) / d + 2; break;
        case b: h = (r - g) / d + 4; break;
      }
      h /= 6;
    };
    s = s * 100;
    s = Math.round(s);
    l = l * 100;
    l = Math.round(l);
    h = Math.round(360 * h);
    document.documentElement.style.setProperty('--ds_color', h + ', ' + s + '%');
    document.documentElement.style.setProperty('--ds_l', l + '%');
  }

}